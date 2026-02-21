package com.insuranceagent.services;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.insuranceagent.api.ApiClient;
import com.insuranceagent.api.ApiService;
import com.insuranceagent.models.Payment;
import com.insuranceagent.receivers.AlarmReceiver;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * ReminderService uses WorkManager to periodically check for:
 *  - Overdue payments
 *  - Due payments in next 3 days
 *  - Policy renewals in next 7 days
 *  - Pending deposit deadlines
 * And schedules local notifications via AlarmManager.
 */
public class ReminderService extends Worker {

    private static final String TAG             = "ReminderService";
    public  static final String CHANNEL_PAYMENTS  = "channel_payments";
    public  static final String CHANNEL_TASKS     = "channel_tasks";
    public  static final String CHANNEL_RENEWALS  = "channel_renewals";
    public  static final String CHANNEL_DEPOSITS  = "channel_deposits";

    private final Context    context;
    private final ApiService apiService;

    public ReminderService(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        this.context    = context;
        this.apiService = ApiClient.getService(context);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "ReminderService: Checking for reminders...");
        createNotificationChannels();
        checkOverduePayments();
        checkUpcomingDuePayments();
        checkPendingDeposits();
        return Result.success();
    }

    // ─── Check Overdue Payments ───────────────────────────────────────────────
    private void checkOverduePayments() {
        apiService.getOverduePayments().enqueue(new Callback<List<Payment>>() {
            @Override
            public void onResponse(Call<List<Payment>> call, Response<List<Payment>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    List<Payment> overdueList = response.body();
                    Log.d(TAG, "Found " + overdueList.size() + " overdue payments");

                    for (Payment payment : overdueList) {
                        sendLocalNotification(
                                CHANNEL_PAYMENTS,
                                "⚠️ Overdue Payment",
                                payment.getClientName() + " — PKR " +
                                        String.format("%.0f", payment.getAmountCollected()) + " overdue",
                                payment.getId().hashCode()
                        );

                        // Also log an alert via API
                        logAlertToBackend("DuePayment",
                                "Overdue Payment",
                                payment.getClientName() + " has an overdue payment of PKR " +
                                        String.format("%.0f", payment.getAmountCollected()),
                                "High", payment.getId());
                    }
                }
            }
            @Override
            public void onFailure(Call<List<Payment>> call, Throwable t) {
                Log.e(TAG, "Failed to fetch overdue payments: " + t.getMessage());
            }
        });
    }

    // ─── Check Upcoming Due Payments (next 3 days) ────────────────────────────
    private void checkUpcomingDuePayments() {
        // Build date range for next 3 days
        Calendar cal = Calendar.getInstance();
        String from  = android.text.format.DateFormat.format("yyyy-MM-dd", cal.getTime()).toString();
        cal.add(Calendar.DAY_OF_YEAR, 3);
        String to    = android.text.format.DateFormat.format("yyyy-MM-dd", cal.getTime()).toString();

        apiService.getPayments("Pending", null, from, to)
                .enqueue(new Callback<List<Payment>>() {
            @Override
            public void onResponse(Call<List<Payment>> call, Response<List<Payment>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    for (Payment p : response.body()) {
                        sendLocalNotification(
                                CHANNEL_PAYMENTS,
                                "📋 Payment Due Soon",
                                p.getClientName() + " — PKR " +
                                        String.format("%.0f", p.getAmountCollected()) + " due in 3 days",
                                ("upcoming_" + p.getId()).hashCode()
                        );
                    }
                }
            }
            @Override
            public void onFailure(Call<List<Payment>> call, Throwable t) {
                Log.e(TAG, "checkUpcomingDuePayments failed: " + t.getMessage());
            }
        });
    }

    // ─── Check Collected But Not Yet Deposited ────────────────────────────────
    private void checkPendingDeposits() {
        apiService.getPendingDeposits().enqueue(new Callback<List<Payment>>() {
            @Override
            public void onResponse(Call<List<Payment>> call, Response<List<Payment>> response) {
                if (response.isSuccessful() && response.body() != null && !response.body().isEmpty()) {
                    int count = response.body().size();
                    sendLocalNotification(
                            CHANNEL_DEPOSITS,
                            "🏦 Pending Deposits",
                            count + " collected payment(s) waiting to be deposited",
                            "deposits".hashCode()
                    );
                }
            }
            @Override
            public void onFailure(Call<List<Payment>> call, Throwable t) {
                Log.e(TAG, "checkPendingDeposits failed: " + t.getMessage());
            }
        });
    }

    // ─── Send Local Notification ──────────────────────────────────────────────
    private void sendLocalNotification(String channelId, String title, String message, int notifId) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        androidx.core.app.NotificationCompat.Builder builder =
                new androidx.core.app.NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true);

        if (nm != null) {
            nm.notify(notifId, builder.build());
        }
    }

    // ─── Log Alert to Backend ─────────────────────────────────────────────────
    private void logAlertToBackend(String type, String title, String message,
                                   String severity, String linkedId) {
        Map<String, String> body = new HashMap<>();
        body.put("type",      type);
        body.put("title",     title);
        body.put("message",   message);
        body.put("severity",  severity);
        body.put("linkedId",  linkedId);
        apiService.sendReminder(body).enqueue(new Callback<Map<String, Object>>() {
            @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) {}
            @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {}
        });
    }

    // ─── Schedule Exact Alarm for a Specific Time ─────────────────────────────
    public static void scheduleAlarm(Context context, long triggerAtMs, String title, String message, int requestCode) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra("title",   title);
        intent.putExtra("message", message);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent);
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent);
        }
    }

    // ─── Create Notification Channels ────────────────────────────────────────
    public static void createNotificationChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_PAYMENTS, "Payment Reminders", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_TASKS, "Task Reminders", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_RENEWALS, "Policy Renewals", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_DEPOSITS, "Deposit Deadlines", NotificationManager.IMPORTANCE_HIGH));
    }

    private void createNotificationChannels() {
        createNotificationChannels(context);
    }

    // ─── Enqueue Periodic Work ─────────────────────────────────────────────────
    public static void schedulePeriodic(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        androidx.work.PeriodicWorkRequest workRequest =
                new androidx.work.PeriodicWorkRequest.Builder(
                        ReminderService.class, 6, TimeUnit.HOURS)
                        .setConstraints(constraints)
                        .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "reminder_check",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                workRequest
        );
    }
}
