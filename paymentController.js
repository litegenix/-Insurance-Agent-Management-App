const { db } = require('../config/firebase');
const COLLECTION = 'payments';

// ─── Get Payments (with filters) ──────────────────────────────────────────────
exports.getPayments = async (req, res) => {
    try {
        const agentId  = req.agent.agentId;
        const { status, clientId, from, to } = req.query;

        let query = db.collection(COLLECTION).where('agentId', '==', agentId);

        if (status)   query = query.where('status', '==', status);
        if (clientId) query = query.where('clientId', '==', clientId);

        query = query.orderBy('dueDate', 'desc').limit(200);

        const snapshot = await query.get();
        let payments = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));

        // Date range filter (Firestore range queries on different fields need client-side filter)
        if (from) payments = payments.filter(p => p.dueDate >= from);
        if (to)   payments = payments.filter(p => p.dueDate <= to);

        // Auto-mark overdue payments
        const today = new Date().toISOString().split('T')[0];
        const updates = [];
        payments = payments.map(p => {
            if (p.status === 'Pending' && p.dueDate && p.dueDate < today) {
                updates.push(db.collection(COLLECTION).doc(p.id).update({ status: 'Overdue' }));
                return { ...p, status: 'Overdue' };
            }
            return p;
        });
        if (updates.length) await Promise.all(updates);

        res.json(payments);
    } catch (err) {
        console.error('getPayments error:', err);
        res.status(500).json({ success: false, message: 'Server error' });
    }
};

// ─── Get Overdue Payments ─────────────────────────────────────────────────────
exports.getOverduePayments = async (req, res) => {
    try {
        const agentId = req.agent.agentId;
        const today   = new Date().toISOString().split('T')[0];

        const snapshot = await db.collection(COLLECTION)
                .where('agentId', '==', agentId)
                .where('status', 'in', ['Pending', 'Overdue'])
                .get();

        const overdue = snapshot.docs
                .map(doc => ({ id: doc.id, ...doc.data() }))
                .filter(p => p.dueDate && p.dueDate < today);

        res.json(overdue);
    } catch (err) {
        console.error('getOverduePayments error:', err);
        res.status(500).json({ success: false, message: 'Server error' });
    }
};

// ─── Get Collected But Not Deposited ──────────────────────────────────────────
exports.getPendingDeposits = async (req, res) => {
    try {
        const snapshot = await db.collection(COLLECTION)
                .where('agentId', '==', req.agent.agentId)
                .where('status', '==', 'Collected')
                .get();

        const pending = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));
        res.json(pending);
    } catch (err) {
        console.error('getPendingDeposits error:', err);
        res.status(500).json({ success: false, message: 'Server error' });
    }
};

// ─── Add Payment Record ───────────────────────────────────────────────────────
exports.addPayment = async (req, res) => {
    try {
        const agentId = req.agent.agentId;
        const {
            clientId, clientName, policyId, policyNumber,
            amountCollected, dueDate, paymentMethod, receiptNumber, notes
        } = req.body;

        if (!clientId || !amountCollected) {
            return res.status(400).json({ success: false, message: 'clientId and amountCollected required' });
        }

        const paymentData = {
            agentId, clientId, clientName, policyId, policyNumber,
            amountCollected: parseFloat(amountCollected),
            amountDeposited: 0,
            collectionDate:  new Date().toISOString().split('T')[0],
            depositDate:     null,
            dueDate:         dueDate || null,
            status:          'Collected',
            paymentMethod:   paymentMethod || 'Cash',
            receiptNumber:   receiptNumber || '',
            notes:           notes || '',
            createdAt:       new Date().toISOString()
        };

        const docRef = await db.collection(COLLECTION).add(paymentData);

        // Update policy's next due date
        if (policyId) {
            await updatePolicyNextDueDate(policyId);
        }

        // Update sales target progress
        await updateTargetProgress(agentId, parseFloat(amountCollected));

        res.status(201).json({ id: docRef.id, ...paymentData });
    } catch (err) {
        console.error('addPayment error:', err);
        res.status(500).json({ success: false, message: 'Server error' });
    }
};

// ─── Mark as Deposited ────────────────────────────────────────────────────────
exports.markDeposited = async (req, res) => {
    try {
        const docRef = db.collection(COLLECTION).doc(req.params.id);
        const doc    = await docRef.get();

        if (!doc.exists || doc.data().agentId !== req.agent.agentId) {
            return res.status(404).json({ success: false, message: 'Payment not found' });
        }

        const { depositDate, amountDeposited } = req.body;
        const updates = {
            status:          'Deposited',
            depositDate:     depositDate || new Date().toISOString().split('T')[0],
            amountDeposited: parseFloat(amountDeposited || doc.data().amountCollected),
            updatedAt:       new Date().toISOString()
        };

        await docRef.update(updates);
        res.json({ id: req.params.id, ...doc.data(), ...updates });

    } catch (err) {
        console.error('markDeposited error:', err);
        res.status(500).json({ success: false, message: 'Server error' });
    }
};

// ─── Update Payment ───────────────────────────────────────────────────────────
exports.updatePayment = async (req, res) => {
    try {
        const docRef = db.collection(COLLECTION).doc(req.params.id);
        const doc    = await docRef.get();

        if (!doc.exists || doc.data().agentId !== req.agent.agentId) {
            return res.status(404).json({ success: false, message: 'Payment not found' });
        }

        const updates = { ...req.body, updatedAt: new Date().toISOString() };
        await docRef.update(updates);
        res.json({ id: req.params.id, ...doc.data(), ...updates });

    } catch (err) {
        console.error('updatePayment error:', err);
        res.status(500).json({ success: false, message: 'Server error' });
    }
};

// ─── Helpers ──────────────────────────────────────────────────────────────────
async function updatePolicyNextDueDate(policyId) {
    const policyRef = db.collection('policies').doc(policyId);
    const policy    = await policyRef.get();
    if (!policy.exists) return;

    const { paymentFrequency, nextDueDate } = policy.data();
    const current = new Date(nextDueDate || new Date());

    const freqMap = { Monthly: 1, Quarterly: 3, 'Semi-Annual': 6, Annual: 12 };
    const months  = freqMap[paymentFrequency] || 1;
    current.setMonth(current.getMonth() + months);

    await policyRef.update({ nextDueDate: current.toISOString().split('T')[0] });
}

async function updateTargetProgress(agentId, amount) {
    const now    = new Date();
    const year   = now.getFullYear();
    const month  = now.getMonth() + 1;

    const targetSnap = await db.collection('sales_targets')
            .where('agentId', '==', agentId)
            .where('year', '==', year)
            .where('month', '==', month)
            .limit(1)
            .get();

    if (!targetSnap.empty) {
        const target    = targetSnap.docs[0];
        const data      = target.data();
        const newAmount = (data.achievedAmount || 0) + amount;
        const commRate  = parseFloat(process.env.DEFAULT_COMMISSION_RATE) || 0.15;

        await target.ref.update({
            achievedAmount:   newAmount,
            achievedPolicies: (data.achievedPolicies || 0) + 1,
            commissionEarned: newAmount * commRate
        });
    }
}
