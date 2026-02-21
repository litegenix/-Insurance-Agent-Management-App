const cron  = require('node-cron');
const { db }= require('../config/firebase');
const { sendSMS, sendEmail, buildPremiumSmsMessage, buildPremiumEmailHtml } = require('./notificationHelper');

/**
 * Automated reminder scheduler.
 * Runs daily at 8:00 AM to:
 *   1. Find policies with payments due in 3 days — send SMS/email to clients
 *   2. Find overdue payments — log alerts for agent
 *   3. Find policies expiring in 30 days — log renewal alerts
 */
exports.startScheduler = () => {
    console.log('⏰ Cron scheduler started');

    // ─── Daily at 8:00 AM ──────────────────────────────────────────────────
    cron.schedule('0 8 * * *', async () => {
        console.log('🔔 Running daily reminder check:', new Date().toISOString());
        await checkDuePayments();
        await checkOverduePayments();
        await checkPolicyRenewals();
    });

    // ─── Every 6 hours: check deposit deadlines ────────────────────────────
    cron.schedule('0 */6 * * *', async () => {
        await checkPendingDeposits();
    });
};

// ─── 1. Payments Due in 3 Days → SMS/Email Client ─────────────────────────────
async function checkDuePayments() {
    try {
        const now      = new Date();
        const threeDays= new Date(now.getTime() + 3 * 24 * 60 * 60 * 1000);
        const today    = toDateStr(now);
        const inThree  = toDateStr(threeDays);

        const snap = await db.collectionGroup('payments')
                .where('status', 'in', ['Pending'])
                .where('dueDate', '>=', today)
                .where('dueDate', '<=', inThree)
                .get();

        console.log(`📋 ${snap.size} payments due in next 3 days`);

        for (const doc of snap.docs) {
            const payment = doc.data();

            // Get client info
            if (!payment.clientId) continue;
            const clientDoc = await db.collection('clients').doc(payment.clientId).get();
            if (!clientDoc.exists) continue;
            const client = clientDoc.data();

            // Get policy info
            let policy = {};
            if (payment.policyId) {
                const policyDoc = await db.collection('policies').doc(payment.policyId).get();
                if (policyDoc.exists) policy = policyDoc.data();
            }

            const smsMsg = buildPremiumSmsMessage(
                client.name, payment.policyNumber || 'N/A',
                payment.amountCollected, payment.dueDate
            );

            // Send SMS if client has phone
            if (client.phoneNumber) {
                const smsResult = await sendSMS(client.phoneNumber, smsMsg);
                await doc.ref.update({ smsSent: smsResult.success });
            }

            // Send Email if client has email
            if (client.email) {
                const html = buildPremiumEmailHtml(
                    client.name, payment.policyNumber || 'N/A',
                    payment.amountCollected, payment.dueDate,
                    policy.policyType || 'Insurance'
                );
                await sendEmail(client.email, '⚠️ Premium Payment Reminder', html);
            }

            // Log alert for agent
            await db.collection('alerts').add({
                agentId:     payment.agentId,
                type:        'DuePayment',
                title:       'Payment Due Soon',
                message:     `${client.name} — PKR ${payment.amountCollected} due on ${payment.dueDate}`,
                severity:    'Medium',
                linkedId:    doc.id,
                isRead:      false,
                smsSent:     !!client.phoneNumber,
                emailSent:   !!client.email,
                scheduledFor:new Date().toISOString(),
                createdAt:   new Date().toISOString()
            });
        }
    } catch (err) {
        console.error('checkDuePayments error:', err);
    }
}

// ─── 2. Overdue Payments → Alert Agent ────────────────────────────────────────
async function checkOverduePayments() {
    try {
        const today = toDateStr(new Date());

        const snap = await db.collectionGroup('payments')
                .where('status', '==', 'Pending')
                .where('dueDate', '<', today)
                .get();

        console.log(`⚠️  ${snap.size} overdue payments found`);

        const batch = db.batch();
        for (const doc of snap.docs) {
            batch.update(doc.ref, { status: 'Overdue' });

            const p = doc.data();
            await db.collection('alerts').add({
                agentId:   p.agentId,
                type:      'Overdue',
                title:     'Overdue Payment',
                message:   `${p.clientName} — PKR ${p.amountCollected} was due on ${p.dueDate}`,
                severity:  'High',
                linkedId:  doc.id,
                isRead:    false,
                createdAt: new Date().toISOString()
            });
        }
        await batch.commit();
    } catch (err) {
        console.error('checkOverduePayments error:', err);
    }
}

// ─── 3. Policies Expiring in 30 Days → Renewal Alert ─────────────────────────
async function checkPolicyRenewals() {
    try {
        const in30 = toDateStr(new Date(Date.now() + 30 * 24 * 60 * 60 * 1000));
        const today = toDateStr(new Date());

        const snap = await db.collectionGroup('policies')
                .where('status', '==', 'Active')
                .where('endDate', '>=', today)
                .where('endDate', '<=', in30)
                .get();

        console.log(`🔄 ${snap.size} policies expiring in 30 days`);

        for (const doc of snap.docs) {
            const policy = doc.data();
            await db.collection('alerts').add({
                agentId:   policy.agentId,
                type:      'PolicyRenewal',
                title:     'Policy Renewal Due',
                message:   `Policy ${policy.policyNumber} expires on ${policy.endDate}`,
                severity:  'Medium',
                linkedId:  doc.id,
                isRead:    false,
                createdAt: new Date().toISOString()
            });
        }
    } catch (err) {
        console.error('checkPolicyRenewals error:', err);
    }
}

// ─── 4. Collected But Not Deposited for 2+ Days ───────────────────────────────
async function checkPendingDeposits() {
    try {
        const twoDaysAgo = toDateStr(new Date(Date.now() - 2 * 24 * 60 * 60 * 1000));

        const snap = await db.collectionGroup('payments')
                .where('status', '==', 'Collected')
                .where('collectionDate', '<=', twoDaysAgo)
                .get();

        if (snap.size > 0) {
            console.log(`🏦 ${snap.size} collected payments pending deposit`);
        }
        // Alerts handled by agent-level check, not per-payment here
    } catch (err) {
        console.error('checkPendingDeposits error:', err);
    }
}

function toDateStr(date) {
    return date.toISOString().split('T')[0];
}
