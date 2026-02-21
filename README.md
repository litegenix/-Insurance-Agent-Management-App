# 🛡️ Insurance Agent Management App
> Java · Android · Node.js REST API · Firebase Firestore + Firebase Auth

---

## Project Structure

```
InsuranceAgent/
│
├── app/                                        ← Android Application (Java)
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/insuranceagent/
│           ├── activities/
│           │   ├── SplashActivity.java              ← App entry point
│           │   ├── LoginActivity.java               ← FR1: Authentication
│           │   ├── RegisterActivity.java            ← FR1: Registration
│           │   ├── MainActivity.java                ← Bottom nav hub
│           │   ├── DashboardActivity.java           ← FR8: Visual dashboard
│           │   ├── ClientListActivity.java          ← FR2: Client list + search
│           │   ├── AddClientActivity.java           ← FR2: Add/edit client
│           │   ├── ClientDetailActivity.java        ← FR2: Client detail + policy
│           │   ├── TaskListActivity.java            ← FR3: Task management
│           │   ├── AddTaskActivity.java             ← FR3: Add/edit task
│           │   ├── PaymentListActivity.java         ← FR4: Payment tracking
│           │   ├── AddPaymentActivity.java          ← FR4: Record payment
│           │   ├── AlertsActivity.java              ← FR5: Alerts & reminders
│           │   ├── PerformanceActivity.java         ← FR6: Targets & commissions
│           │   ├── ScheduleActivity.java            ← FR7: Calendar/meetings
│           │   ├── ReportsActivity.java             ← FR8: Reports + export
│           │   └── BackupRestoreActivity.java       ← FR9: Backup & restore
│           ├── adapters/
│           │   ├── ClientAdapter.java
│           │   ├── TaskAdapter.java
│           │   ├── PaymentAdapter.java
│           │   └── AlertAdapter.java
│           ├── models/
│           │   ├── Agent.java
│           │   ├── Client.java
│           │   ├── Policy.java
│           │   ├── Payment.java
│           │   ├── Task.java
│           │   ├── Alert.java
│           │   ├── SalesTarget.java
│           │   └── Meeting.java
│           ├── api/
│           │   ├── ApiClient.java                   ← Retrofit + JWT interceptor
│           │   └── ApiService.java                  ← All REST endpoints
│           ├── services/
│           │   ├── ReminderService.java             ← FR5: Background reminders
│           │   └── SmsEmailService.java             ← FR5: Client SMS/email
│           ├── receivers/
│           │   ├── AlarmReceiver.java               ← Scheduled alarm handler
│           │   └── BootReceiver.java                ← Restart alarms on boot
│           ├── viewmodels/
│           │   ├── ClientViewModel.java
│           │   ├── PaymentViewModel.java
│           │   └── DashboardViewModel.java
│           └── utils/
│               ├── SessionManager.java
│               ├── DateUtils.java
│               ├── CurrencyUtils.java
│               ├── PdfExporter.java                 ← FR8: PDF export
│               └── ExcelExporter.java               ← FR8: Excel export
│
├── backend/                                    ← Node.js REST API
│   ├── server.js                               ← Express app entry point
│   ├── schema.sql                              ← MySQL DB schema (alternative)
│   ├── package.json
│   ├── .env
│   ├── config/
│   │   ├── db.js                               ← Firebase Admin SDK setup
│   │   └── firebase.js                         ← Firebase config
│   ├── middleware/
│   │   └── auth.js                             ← JWT verification middleware
│   ├── controllers/
│   │   ├── authController.js                   ← Register, login
│   │   ├── clientController.js                 ← Client CRUD + search
│   │   ├── policyController.js                 ← Policy management
│   │   ├── paymentController.js                ← Payment tracking
│   │   ├── taskController.js                   ← Task management
│   │   ├── alertController.js                  ← Alerts & reminders
│   │   ├── performanceController.js            ← Targets & commissions
│   │   └── reportController.js                 ← Report generation
│   └── utils/
│       ├── scheduler.js                        ← Cron jobs for auto-reminders
│       └── notificationHelper.js               ← FCM push notification sender
```

---

## Functional Requirements Coverage

| FR  | Feature                    | Android Implementation               | Backend                          |
|-----|----------------------------|--------------------------------------|----------------------------------|
| FR1 | Auth                       | LoginActivity + Firebase Auth        | authController + JWT             |
| FR2 | Client & Policy            | ClientListActivity + search bar      | clientController + policyController |
| FR3 | Task Management            | TaskListActivity + priority tags     | taskController                   |
| FR4 | Payment Tracking           | PaymentListActivity + overdue flags  | paymentController                |
| FR5 | Alerts & Reminders         | AlarmManager + ReminderService       | scheduler.js + FCM               |
| FR6 | Performance & Targets      | PerformanceActivity + charts         | performanceController            |
| FR7 | Scheduling                 | ScheduleActivity + CalendarView      | meetingController                |
| FR8 | Dashboard & Reports        | DashboardActivity + MPAndroidChart   | reportController                 |
| FR9 | Backup & Restore           | BackupRestoreActivity + Firestore    | Firestore auto-sync              |

---

## Tech Stack

| Layer        | Technology                          | Reason                                                |
|--------------|-------------------------------------|-------------------------------------------------------|
| Language     | Java (Android)                      | Required per project spec                             |
| Backend      | Node.js + Express                   | Lightweight, fast REST API                            |
| Database     | Firebase Firestore                  | Real-time sync, offline support, cloud backup built-in|
| Auth         | Firebase Authentication             | Secure, supports email + phone auth                   |
| Push Notifs  | Firebase Cloud Messaging (FCM)      | Free push notifications to Android devices            |
| Charts       | MPAndroidChart                      | Professional charts for dashboard                     |
| PDF Export   | iText / PdfDocument (Android)       | Generate PDF reports on-device                        |
| Excel Export | Apache POI (via backend)            | Generate Excel reports server-side                    |
| Scheduling   | Android AlarmManager + WorkManager  | Reliable background reminders                         |
| SMS          | Twilio API (backend)                | Send SMS to clients                                   |
| Email        | Nodemailer (backend)                | Send email reminders to clients                       |

---

## Setup Instructions

### 1. Firebase Setup (Required First)
1. Go to https://console.firebase.google.com
2. Create a new project: "InsuranceAgentApp"
3. Add an Android app with package name: `com.insuranceagent`
4. Download `google-services.json` → place in `app/` folder
5. Enable **Authentication** → Email/Password
6. Enable **Firestore Database** → Start in test mode
7. Enable **Cloud Messaging** (FCM)

### 2. Backend Setup
```bash
cd backend
npm install
cp .env.example .env
# Fill in your Firebase Admin SDK credentials, Twilio keys, email config
npm run dev
```

### 3. Android Setup
1. Open `InsuranceAgent/` in Android Studio
2. Place `google-services.json` in `app/` directory
3. Update `BASE_URL` in `ApiClient.java` to your backend IP
4. Run on emulator or physical device (API 26+)

---

## API Endpoints Reference

| Method | Endpoint                          | Description                        |
|--------|-----------------------------------|------------------------------------|
| POST   | /api/auth/register                | Agent registration                 |
| POST   | /api/auth/login                   | Login + get JWT                    |
| GET    | /api/clients                      | List all clients (paginated)       |
| POST   | /api/clients                      | Add new client                     |
| GET    | /api/clients/search?q=            | Search by name/CNIC/phone/policy   |
| GET    | /api/clients/:id                  | Get client detail                  |
| PUT    | /api/clients/:id                  | Update client                      |
| DELETE | /api/clients/:id                  | Delete client                      |
| GET    | /api/policies/:clientId           | Get policies for client            |
| POST   | /api/policies                     | Add policy                         |
| GET    | /api/payments                     | All payments (with filters)        |
| POST   | /api/payments                     | Record new payment                 |
| GET    | /api/payments/overdue             | Get overdue payments               |
| GET    | /api/tasks                        | Get agent's tasks                  |
| POST   | /api/tasks                        | Create task                        |
| PUT    | /api/tasks/:id                    | Update/complete task               |
| GET    | /api/alerts                       | Get all alerts                     |
| POST   | /api/alerts/send-reminder         | Send SMS/email to client           |
| GET    | /api/performance/summary          | Dashboard summary stats            |
| GET    | /api/performance/targets          | Sales targets progress             |
| GET    | /api/reports/generate             | Generate report data               |
| POST   | /api/reports/export/pdf           | Export PDF report                  |
| POST   | /api/reports/export/excel         | Export Excel report                |

---

## Key Design Decisions

| Decision              | Choice                          | Reason                                               |
|-----------------------|---------------------------------|------------------------------------------------------|
| Database              | Firestore                       | Built-in offline mode = FR9 backup/restore for free  |
| Reminders             | AlarmManager + WorkManager      | AlarmManager for exact alarms, WorkManager for retry |
| SMS to clients        | Twilio (server-side)            | Reliable delivery, not dependent on device carrier   |
| Report export         | PDF on-device, Excel on server  | PDF is simple on Android; Excel needs POI library    |
| Search                | Firestore compound queries      | Fast, indexed search across multiple fields          |
| Commission tracking   | Calculated server-side          | Single source of truth, prevents client-side tampering|
