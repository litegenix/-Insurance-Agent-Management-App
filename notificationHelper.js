require('dotenv').config();
const twilio    = require('twilio');
const nodemailer= require('nodemailer');

// ─── Twilio SMS Client ────────────────────────────────────────────────────────
let twilioClient = null;
try {
    twilioClient = twilio(
        process.env.TWILIO_ACCOUNT_SID,
        process.env.TWILIO_AUTH_TOKEN
    );
} catch (e) {
    console.warn('⚠️  Twilio not configured — SMS will be disabled');
}

// ─── Nodemailer Email Transporter ─────────────────────────────────────────────
let emailTransporter = null;
try {
    emailTransporter = nodemailer.createTransporter({
        host:   process.env.EMAIL_HOST  || 'smtp.gmail.com',
        port:   parseInt(process.env.EMAIL_PORT) || 587,
        secure: false,
        auth: {
            user: process.env.EMAIL_USER,
            pass: process.env.EMAIL_PASS,
        },
    });
} catch (e) {
    console.warn('⚠️  Email transporter not configured');
}

// ─── Send SMS to Client ───────────────────────────────────────────────────────
/**
 * @param {string} toPhone   - Client's phone number (e.g. +923001234567)
 * @param {string} message   - SMS message body
 * @returns {Promise<{success: boolean, sid?: string, error?: string}>}
 */
exports.sendSMS = async (toPhone, message) => {
    if (!twilioClient) {
        console.log('[SMS Disabled] Would have sent to', toPhone, ':', message);
        return { success: false, error: 'SMS not configured' };
    }

    try {
        // Format Pakistani number if needed
        const formatted = formatPakistaniPhone(toPhone);

        const result = await twilioClient.messages.create({
            body: message,
            from: process.env.TWILIO_PHONE_NUMBER,
            to:   formatted
        });

        console.log(`✅ SMS sent to ${formatted} — SID: ${result.sid}`);
        return { success: true, sid: result.sid };

    } catch (err) {
        console.error('SMS send failed:', err.message);
        return { success: false, error: err.message };
    }
};

// ─── Send Email to Client ──────────────────────────────────────────────────────
/**
 * @param {string} toEmail  - Client's email
 * @param {string} subject  - Email subject
 * @param {string} htmlBody - HTML email body
 */
exports.sendEmail = async (toEmail, subject, htmlBody) => {
    if (!emailTransporter) {
        console.log('[Email Disabled] Would have sent to', toEmail, '— Subject:', subject);
        return { success: false, error: 'Email not configured' };
    }

    try {
        const info = await emailTransporter.sendMail({
            from:    process.env.EMAIL_FROM || 'Insurance Agent App <noreply@example.com>',
            to:      toEmail,
            subject: subject,
            html:    htmlBody
        });

        console.log(`✅ Email sent to ${toEmail} — MessageId: ${info.messageId}`);
        return { success: true, messageId: info.messageId };

    } catch (err) {
        console.error('Email send failed:', err.message);
        return { success: false, error: err.message };
    }
};

// ─── Premium Due Reminder Templates ───────────────────────────────────────────
exports.buildPremiumSmsMessage = (clientName, policyNumber, amount, dueDate) =>
    `Dear ${clientName}, your insurance premium of PKR ${amount.toLocaleString()} ` +
    `for Policy ${policyNumber} is due on ${dueDate}. ` +
    `Please ensure timely payment to keep your policy active. Thank you.`;

exports.buildPremiumEmailHtml = (clientName, policyNumber, amount, dueDate, policyType) => `
<!DOCTYPE html>
<html>
<body style="font-family: Arial, sans-serif; max-width: 600px; margin: auto; padding: 20px;">
  <div style="background: #1565C0; color: white; padding: 20px; border-radius: 8px 8px 0 0;">
    <h2 style="margin:0;">🛡️ Premium Payment Reminder</h2>
  </div>
  <div style="background: #f9f9f9; padding: 20px; border: 1px solid #ddd;">
    <p>Dear <strong>${clientName}</strong>,</p>
    <p>This is a friendly reminder that your insurance premium payment is due soon.</p>
    <table style="width:100%; border-collapse: collapse; margin: 16px 0;">
      <tr><td style="padding:8px; background:#fff; border:1px solid #ddd;"><strong>Policy Number</strong></td><td style="padding:8px; border:1px solid #ddd;">${policyNumber}</td></tr>
      <tr><td style="padding:8px; background:#fff; border:1px solid #ddd;"><strong>Policy Type</strong></td><td style="padding:8px; border:1px solid #ddd;">${policyType}</td></tr>
      <tr><td style="padding:8px; background:#fff; border:1px solid #ddd;"><strong>Amount Due</strong></td><td style="padding:8px; border:1px solid #ddd; color:#D32F2F;"><strong>PKR ${amount.toLocaleString()}</strong></td></tr>
      <tr><td style="padding:8px; background:#fff; border:1px solid #ddd;"><strong>Due Date</strong></td><td style="padding:8px; border:1px solid #ddd;">${dueDate}</td></tr>
    </table>
    <p>Please ensure timely payment to keep your policy active and avoid any lapse in coverage.</p>
    <p>If you have already made the payment, please disregard this reminder.</p>
    <p>For any queries, please contact your insurance agent.</p>
  </div>
  <div style="background: #eee; padding: 10px; text-align: center; font-size: 12px; color: #666; border-radius: 0 0 8px 8px;">
    Insurance Agent Management System
  </div>
</body>
</html>`;

// ─── Format Pakistani Phone Number for Twilio ────────────────────────────────
function formatPakistaniPhone(phone) {
    const cleaned = phone.replace(/\D/g, '');
    if (cleaned.startsWith('92')) return '+' + cleaned;
    if (cleaned.startsWith('0'))  return '+92' + cleaned.substring(1);
    return '+92' + cleaned;
}
