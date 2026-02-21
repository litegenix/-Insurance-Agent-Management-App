require('dotenv').config();
const express  = require('express');
const cors     = require('cors');
const jwt      = require('jsonwebtoken');
const bcrypt   = require('bcryptjs');
const { db, auth, messaging } = require('./config/firebase');
const { authenticate }         = require('./middleware/auth');
const clientController         = require('./controllers/clientController');
const paymentController        = require('./controllers/paymentController');
const reportController         = require('./controllers/reportController');
const { getPerformanceSummary, getSalesTargets, setTarget } = require('./controllers/reportController');
const { sendSMS, sendEmail, buildPremiumSmsMessage, buildPremiumEmailHtml } = require('./utils/notificationHelper');
const { startScheduler }       = require('./utils/scheduler');

const app  = express();
const PORT = process.env.PORT || 3000;

// ─── Middleware ───────────────────────────────────────────────────────────────
app.use(cors());
app.use(express.json());

// ─── AUTH ROUTES ─────────────────────────────────────────────────────────────
app.post('/api/auth/register', async (req, res) => {
    try {
        const { name, email, phone, password, agentCode, company } = req.body;
        if (!name || !email || !password) {
            return res.status(400).json({ success: false, message: 'Name, email and password required' });
        }

        // Create Firebase Auth user
        const firebaseUser = await auth.createUser({ email, password, displayName: name });

        const hashed = await bcrypt.hash(password, 12);

        // Save agent profile to Firestore
        const agentData = {
            uid: firebaseUser.uid, name, email, phone, agentCode, company,
            commissionRate: parseFloat(process.env.DEFAULT_COMMISSION_RATE) || 0.15,
            role: 'agent',
            createdAt: new Date().toISOString()
        };
        const docRef = await db.collection('agents').add(agentData);

        const token = jwt.sign(
            { agentId: docRef.id, email, agentCode, role: 'agent' },
            process.env.JWT_SECRET, { expiresIn: process.env.JWT_EXPIRES_IN }
        );

        res.status(201).json({ success: true, token, agent: { id: docRef.id, ...agentData } });

    } catch (err) {
        console.error('Register error:', err);
        if (err.code === 'auth/email-already-exists') {
            return res.status(409).json({ success: false, message: 'Email already registered' });
        }
        res.status(500).json({ success: false, message: 'Server error' });
    }
});

app.post('/api/auth/login', async (req, res) => {
    try {
        const { email, firebase_token } = req.body;

        // Verify Firebase token
        const decoded = await auth.verifyIdToken(firebase_token);
        if (decoded.email !== email) {
            return res.status(401).json({ success: false, message: 'Token mismatch' });
        }

        // Find agent in Firestore
        const agentSnap = await db.collection('agents').where('email', '==', email).limit(1).get();
        if (agentSnap.empty) {
            return res.status(404).json({ success: false, message: 'Agent profile not found' });
        }

        const agentDoc = agentSnap.docs[0];
        const agent    = { id: agentDoc.id, ...agentDoc.data() };

        const token = jwt.sign(
            { agentId: agent.id, email: agent.email, agentCode: agent.agentCode, role: agent.role },
            process.env.JWT_SECRET, { expiresIn: process.env.JWT_EXPIRES_IN }
        );

        res.json({ success: true, token, agent });

    } catch (err) {
        console.error('Login error:', err);
        res.status(401).json({ success: false, message: 'Authentication failed' });
    }
});

app.get('/api/auth/profile', authenticate, async (req, res) => {
    const snap = await db.collection('agents').doc(req.agent.agentId).get();
    if (!snap.exists) return res.status(404).json({ success: false, message: 'Not found' });
    res.json({ success: true, data: { id: snap.id, ...snap.data() } });
});

// ─── CLIENT ROUTES ────────────────────────────────────────────────────────────
app.get(   '/api/clients',          authenticate, clientController.getClients);
app.get(   '/api/clients/search',   authenticate, clientController.searchClients);
app.get(   '/api/clients/:id',      authenticate, clientController.getClient);
app.post(  '/api/clients',          authenticate, clientController.addClient);
app.put(   '/api/clients/:id',      authenticate, clientController.updateClient);
app.delete('/api/clients/:id',      authenticate, clientController.deleteClient);

// ─── POLICY ROUTES ────────────────────────────────────────────────────────────
app.get('/api/policies/:clientId', authenticate, async (req, res) => {
    try {
        const snap = await db.collection('policies')
                .where('clientId', '==', req.params.clientId)
                .where('agentId',  '==', req.agent.agentId)
                .get();
        res.json(snap.docs.map(d => ({ id: d.id, ...d.data() })));
    } catch (err) { res.status(500).json({ success: false, message: 'Server error' }); }
});

app.post('/api/policies', authenticate, async (req, res) => {
    try {
        const data = { ...req.body, agentId: req.agent.agentId, createdAt: new Date().toISOString() };
        const ref  = await db.collection('policies').add(data);
        res.status(201).json({ id: ref.id, ...data });
    } catch (err) { res.status(500).json({ success: false, message: 'Server error' }); }
});

app.put('/api/policies/:id', authenticate, async (req, res) => {
    try {
        await db.collection('policies').doc(req.params.id).update(req.body);
        res.json({ id: req.params.id, ...req.body });
    } catch (err) { res.status(500).json({ success: false, message: 'Server error' }); }
});

// ─── PAYMENT ROUTES ───────────────────────────────────────────────────────────
app.get(  '/api/payments',                  authenticate, paymentController.getPayments);
app.get(  '/api/payments/overdue',          authenticate, paymentController.getOverduePayments);
app.get(  '/api/payments/pending-deposit',  authenticate, paymentController.getPendingDeposits);
app.post( '/api/payments',                  authenticate, paymentController.addPayment);
app.put(  '/api/payments/:id',              authenticate, paymentController.updatePayment);
app.put(  '/api/payments/:id/mark-deposited', authenticate, paymentController.markDeposited);

// ─── TASK ROUTES ──────────────────────────────────────────────────────────────
app.get('/api/tasks', authenticate, async (req, res) => {
    try {
        const { status, priority } = req.query;
        let q = db.collection('tasks').where('agentId', '==', req.agent.agentId);
        if (status)   q = q.where('status',   '==', status);
        if (priority) q = q.where('priority', '==', priority);
        q = q.orderBy('dueDate', 'asc');
        const snap = await q.get();
        res.json(snap.docs.map(d => ({ id: d.id, ...d.data() })));
    } catch (err) { res.status(500).json({ success: false, message: 'Server error' }); }
});

app.post('/api/tasks', authenticate, async (req, res) => {
    try {
        const data = { ...req.body, agentId: req.agent.agentId, status: 'Pending', createdAt: new Date().toISOString() };
        const ref  = await db.collection('tasks').add(data);
        res.status(201).json({ id: ref.id, ...data });
    } catch (err) { res.status(500).json({ success: false, message: 'Server error' }); }
});

app.put('/api/tasks/:id/complete', authenticate, async (req, res) => {
    try {
        await db.collection('tasks').doc(req.params.id)
                .update({ status: 'Completed', completedAt: new Date().toISOString() });
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: 'Server error' }); }
});

app.put('/api/tasks/:id', authenticate, async (req, res) => {
    try {
        await db.collection('tasks').doc(req.params.id).update(req.body);
        res.json({ id: req.params.id, ...req.body });
    } catch (err) { res.status(500).json({ success: false, message: 'Server error' }); }
});

app.delete('/api/tasks/:id', authenticate, async (req, res) => {
    try {
        await db.collection('tasks').doc(req.params.id).delete();
        res.json({ success: true, message: 'Task deleted' });
    } catch (err) { res.status(500).json({ success: false, message: 'Server error' }); }
});

// ─── ALERTS ROUTES ────────────────────────────────────────────────────────────
app.get('/api/alerts', authenticate, async (req, res) => {
    try {
        const { isRead } = req.query;
        let q = db.collection('alerts').where('agentId', '==', req.agent.agentId)
                .orderBy('createdAt', 'desc').limit(100);
        const snap  = await q.get();
        let alerts  = snap.docs.map(d => ({ id: d.id, ...d.data() }));
        if (isRead !== undefined) alerts = alerts.filter(a => a.isRead === (isRead === 'true'));
        res.json(alerts);
    } catch (err) { res.status(500).json({ success: false, message: 'Server error' }); }
});

app.put('/api/alerts/:id/read', authenticate, async (req, res) => {
    try {
        await db.collection('alerts').doc(req.params.id).update({ isRead: true });
        res.json({ success: true });
    } catch (err) { res.status(500).json({ success: false, message: 'Server error' }); }
});

// Send reminder SMS/email to client
app.post('/api/alerts/send-reminder', authenticate, async (req, res) => {
    try {
        const { clientId, paymentId, type = 'sms' } = req.body;

        const clientDoc = await db.collection('clients').doc(clientId).get();
        if (!clientDoc.exists) return res.status(404).json({ success: false, message: 'Client not found' });
        const client = clientDoc.data();

        let paymentData = {};
        if (paymentId) {
            const payDoc = await db.collection('payments').doc(paymentId).get();
            if (payDoc.exists) paymentData = payDoc.data();
        }

        let result = { success: false };
        if (type === 'sms' && client.phoneNumber) {
            const msg = buildPremiumSmsMessage(client.name,
                paymentData.policyNumber || 'N/A',
                paymentData.amountCollected || 0,
                paymentData.dueDate || 'N/A'
            );
            result = await sendSMS(client.phoneNumber, msg);
        } else if (type === 'email' && client.email) {
            const html = buildPremiumEmailHtml(
                client.name, paymentData.policyNumber || 'N/A',
                paymentData.amountCollected || 0,
                paymentData.dueDate || 'N/A', 'Insurance'
            );
            result = await sendEmail(client.email, '⚠️ Premium Payment Reminder', html);
        }

        // Log the alert
        await db.collection('alerts').add({
            agentId:   req.agent.agentId,
            type:      'ManualReminder',
            title:     `Manual Reminder Sent`,
            message:   `Reminder sent to ${client.name} via ${type}`,
            severity:  'Low',
            linkedId:  clientId,
            isRead:    false,
            smsSent:   type === 'sms',
            emailSent: type === 'email',
            createdAt: new Date().toISOString()
        });

        res.json({ success: true, ...result });
    } catch (err) {
        console.error('send-reminder error:', err);
        res.status(500).json({ success: false, message: 'Server error' });
    }
});

// ─── PERFORMANCE ROUTES ───────────────────────────────────────────────────────
app.get( '/api/performance/summary', authenticate, async (req, res) => {
    // inline to avoid circular require
    const { period='monthly', year=new Date().getFullYear(), month=new Date().getMonth()+1 } = req.query;
    req.query.year  = parseInt(year);
    req.query.month = parseInt(month);
    await reportController.getPerformanceSummary ? reportController.getPerformanceSummary(req, res) : res.json({});
});
app.get( '/api/performance/targets', authenticate, async (req, res) => {
    try {
        const year = parseInt(req.query.year) || new Date().getFullYear();
        const snap = await db.collection('sales_targets')
                .where('agentId','==',req.agent.agentId).where('year','==',year).get();
        res.json(snap.docs.map(d => ({ id: d.id, ...d.data() })));
    } catch (err) { res.status(500).json({ success: false, message: 'Server error' }); }
});
app.post('/api/performance/targets', authenticate, async (req, res) => {
    try {
        const data = { ...req.body, agentId: req.agent.agentId, achievedAmount: 0, achievedPolicies: 0, commissionEarned: 0 };
        const ref  = await db.collection('sales_targets').add(data);
        res.status(201).json({ id: ref.id, ...data });
    } catch (err) { res.status(500).json({ success: false, message: 'Server error' }); }
});

// ─── MEETINGS ROUTES ──────────────────────────────────────────────────────────
app.get('/api/meetings', authenticate, async (req, res) => {
    try {
        const { from, to } = req.query;
        let q = db.collection('meetings').where('agentId', '==', req.agent.agentId).orderBy('meetingDate', 'asc');
        const snap = await q.get();
        let meetings = snap.docs.map(d => ({ id: d.id, ...d.data() }));
        if (from) meetings = meetings.filter(m => m.meetingDate >= from);
        if (to)   meetings = meetings.filter(m => m.meetingDate <= to);
        res.json(meetings);
    } catch (err) { res.status(500).json({ success: false, message: 'Server error' }); }
});
app.post('/api/meetings', authenticate, async (req, res) => {
    try {
        const data = { ...req.body, agentId: req.agent.agentId, createdAt: new Date().toISOString() };
        const ref  = await db.collection('meetings').add(data);
        res.status(201).json({ id: ref.id, ...data });
    } catch (err) { res.status(500).json({ success: false, message: 'Server error' }); }
});

// ─── REPORTS ROUTES ───────────────────────────────────────────────────────────
app.get('/api/reports/dashboard',    authenticate, reportController.getDashboardData);
app.get('/api/reports/export/pdf',   authenticate, reportController.exportPdf);
app.get('/api/reports/export/excel', authenticate, reportController.exportExcel);

// ─── HEALTH CHECK ─────────────────────────────────────────────────────────────
app.get('/api/health', (req, res) => res.json({ status: 'ok', timestamp: new Date() }));

app.use((req, res) => res.status(404).json({ success: false, message: 'Route not found' }));

// ─── START SERVER ─────────────────────────────────────────────────────────────
app.listen(PORT, () => {
    console.log(`🚀 Insurance Agent API → http://localhost:${PORT}`);
    startScheduler();   // Start cron jobs for automated reminders
});

module.exports = app;
