# 📚 StudyPlanner — Smart Study Scheduling App

**Plan smarter. Study better. Achieve more.** 🚀

StudyPlanner is an Android application designed to help students organize their academic activities, manage study time, and build personalized weekly study schedules. It combines subject management, task tracking, study availability, and automatic schedule generation in one place.

Whether you're preparing for exams, completing assignments, or building consistent study habits, StudyPlanner helps you stay organized and make better use of your time.

---

## ✨ Key Features

### 🔐 1. Account & Profile Management

* Create an account and sign in securely.
* Manage your profile and access your personal study data.
* Firebase Authentication handles account authentication.

### 📖 2. Subject Management

* Add and manage your academic subjects.
* Set weekly study-hour targets for each subject.
* Assign priorities: Low, Medium, or High.
* Use subject priorities to guide automatic study scheduling.

### ✅ 3. Task Management

* Create tasks associated with specific subjects.
* Add titles, descriptions, and priorities.
* Set optional due dates and times.
* Mark tasks as completed and manage pending work.

### 🕒 4. Study Availability

* Define the days and times you're available to study.
* Set recurring weekly availability.
* Add availability for specific dates.
* Edit or remove availability whenever your plans change.

### 🤖 5. Automatic Study Schedule Generation

Generate a personalized study plan based on your academic needs and available time.

The planner considers:

* Your available study hours.
* Subject priorities and weekly study targets.
* Tasks and their deadlines.
* Existing study sessions.
* Time-slot availability and scheduling conflicts.

**How it works:**

1. Add your subjects and study targets.
2. Create tasks and set deadlines.
3. Configure your study availability.
4. Generate a proposed study schedule.
5. Review the proposed sessions before saving.
6. Check any activities that could not fit into your available time.

The result is a more organized study routine that helps you prioritize important work.

### 📅 6. Manual Study Schedule Management

* Add study sessions manually.
* Edit session details and timings.
* Delete sessions when plans change.
* Organize your study activities by date and time.

### 📊 7. Progress Tracking

* View daily study progress.
* Track completed tasks and study sessions.
* Check pending activities.
* See the number of sessions scheduled for the day.

### 🔔 8. Study Reminders & Notifications

* Enable reminders for scheduled study sessions.
* Receive notifications before sessions when supported and configured.
* Manage notification permissions through Android settings.

### 🏠 9. Home Dashboard

Get a quick overview of your study activities from a single screen.

The dashboard provides:

* Daily study progress.
* Upcoming study sessions.
* Subject information.
* Available study time.
* Daily inspiration to help you stay motivated.

---

## 🔄 How StudyPlanner Works

The application follows a simple workflow to help students plan their studies effectively.

```text
        ┌──────────────────────┐
        │  Create Account /     │
        │       Sign In         │
        └──────────┬───────────┘
                   │
                   ▼
        ┌──────────────────────┐
        │   Add Your Subjects   │
        │  Targets & Priorities │
        └──────────┬───────────┘
                   │
                   ▼
        ┌──────────────────────┐
        │   Create Study Tasks  │
        │   & Set Deadlines     │
        └──────────┬───────────┘
                   │
                   ▼
        ┌──────────────────────┐
        │  Set Study Availability│
        │   Days & Time Slots   │
        └──────────┬───────────┘
                   │
                   ▼
        ┌──────────────────────┐
        │ Generate Study Plan   │
        │   & Review Sessions   │
        └──────────┬───────────┘
                   │
                   ▼
        ┌──────────────────────┐
        │ Save & Manage Sessions│
        └──────────┬───────────┘
                   │
                   ▼
        ┌──────────────────────┐
        │ Track Progress & Stay │
        │       Motivated       │
        └──────────────────────┘
```

**The goal:** Turn your academic tasks and available time into a manageable study routine.

---

## 🛠️ Tech Stack

StudyPlanner is built using modern Android development technologies.

| Technology                  | Purpose                                     |
| --------------------------- | ------------------------------------------- |
| **Kotlin**                  | Primary programming language                |
| **Android SDK**             | Android application development             |
| **AndroidX**                | Android libraries and compatibility support |
| **Material Components**     | Modern and consistent UI components         |
| **Navigation**              | Navigation between application screens      |
| **Firebase Authentication** | User authentication                         |
| **Cloud Firestore**         | Cloud-based storage for study data          |
| **Gradle**                  | Build automation and dependency management  |

---

## 📋 Requirements

Before running the project, make sure you have the following installed:

* **Android Studio** — for development and debugging.
* **JDK 17** — Java Development Kit.
* **Android SDK 35** — required compile SDK.
* **Firebase Project** — for authentication and cloud data storage.
* **Android Emulator or Physical Device** — for running the application.

**Minimum Android version:** Android 9.0 (API 28).

---

## 🚀 Getting Started

Follow these steps to set up StudyPlanner on your local machine.

### 1. Clone the Repository

Open a terminal and run:

```bash
git clone <YOUR_REPOSITORY_URL>
```

Replace `<YOUR_REPOSITORY_URL>` with your actual GitHub repository URL.

Navigate to the project directory:

```bash
cd StudyPlanner
```

### 2. Open in Android Studio

1. Launch Android Studio.
2. Select **Open**.
3. Choose the cloned `StudyPlanner` project folder.
4. Allow Gradle to sync and download the required dependencies.

### 3. Configure Firebase

StudyPlanner uses Firebase Authentication and Cloud Firestore.

1. Open the [Firebase Console](https://console.firebase.google.com/).

2. Create a Firebase project.

3. Register an Android app using the package name:

   `com.example.studyplanner`

4. Download the `google-services.json` configuration file.

5. Place the file inside the application's `app/` directory.

Your project structure should look similar to this:

```text
StudyPlanner/
├── app/
│   ├── google-services.json
│   ├── src/
│   └── build.gradle.kts
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
└── gradlew.bat
```

*Your exact project structure may differ depending on your Gradle configuration.*

**Important:** Never commit private credentials or sensitive configuration files to a public repository. Review your Firebase configuration and security requirements before publishing the project.

### 4. Enable Firebase Services

In your Firebase project:

* Enable **Email/Password** authentication under Authentication.
* Create a **Cloud Firestore** database.
* Configure appropriate Firestore security rules.
* Add any required Firestore indexes if prompted.

Do not use unrestricted Firestore rules in a production application. Ensure users can access only the data they are authorized to read or modify.

### 5. Run the Application

1. Connect an Android phone or start an emulator.
2. Select your device in Android Studio.
3. Click **Run ▶**.
4. Create an account and start organizing your study activities.

---

## 🏗️ Build from the Command Line

You can build the debug APK without using the Android Studio interface.

### Windows PowerShell

```powershell
.\gradlew.bat assembleDebug
```

### Install on a Connected Android Device

```powershell
.\gradlew.bat installDebug
```

After a successful debug build, the APK is typically generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The exact output location may vary if your build configuration has been customized.

---

## 📸 Screenshots

Screenshots help visitors understand the application before downloading or exploring the code.

Add screenshots of your actual application to your repository and replace the placeholder paths below.

| Home Dashboard                                    | Subject Management                              |
| ------------------------------------------------- | ----------------------------------------------- |
| ![Home Dashboard](screenshots/home_dashboard.png) | ![Subject Management](screenshots/subjects.png) |

| Task Management                           | Study Schedule                                    |
| ----------------------------------------- | ------------------------------------------------- |
| ![Task Management](screenshots/tasks.png) | ![Study Schedule](screenshots/study_schedule.png) |

| Progress Tracking                              | Profile                             |
| ---------------------------------------------- | ----------------------------------- |
| ![Progress Tracking](screenshots/progress.png) | ![Profile](screenshots/profile.png) |

**To add screenshots:**

1. Create a folder named `screenshots` in the project root.
2. Capture screenshots from your running application.
3. Save them using the filenames shown above, or update the README paths to match your filenames.
4. Commit and push the images to GitHub.

*The screenshot paths above are placeholders. Images will appear only after you add the corresponding files.*

---

## 🎯 Project Goals

StudyPlanner aims to make personal study planning easier by bringing several academic organization tools together.

Its primary goals are to:

* Help students manage their subjects and academic tasks.
* Encourage consistent study habits through weekly planning.
* Allocate study time according to priorities and deadlines.
* Reduce scheduling conflicts and make better use of available time.
* Provide visibility into daily progress.
* Help students stay organized and motivated throughout their academic journey.

---

## 🔮 Future Enhancements

Potential improvements for future versions include:

* 📈 Weekly and monthly progress analytics.
* 📆 Calendar integration and advanced schedule visualization.
* 🔄 Offline support and improved data synchronization.
* 🎯 Personalized study recommendations.
* 📚 Exam preparation and revision planning.
* 🎨 Additional themes and UI customization.
* 📤 Exporting study schedules and progress reports.

*These are potential future improvements, not claims about features already implemented.*

---

## 🤝 Contributing

Contributions, suggestions, and feedback are welcome!

If you would like to contribute:

1. Fork the repository.
2. Create a new branch for your changes.
3. Implement and test your improvements.
4. Commit your changes with a descriptive message.
5. Open a pull request describing your contribution.

For major changes, consider opening an issue first to discuss the proposed improvement.

---

## 👨‍💻 About the Project

StudyPlanner is an Android development project focused on helping students organize their academic responsibilities through structured planning, task management, and progress tracking.

It also provides practical experience with Kotlin, Android application architecture, Firebase integration, and cloud-based data management.

---

## 📄 License

No license has been specified yet.

If you intend to make the project open source, consider adding an appropriate license file, such as the MIT License, after deciding how you want others to use your code.

---

### ⭐ Support the Project

If you find StudyPlanner useful or interesting, consider giving the repository a ⭐ on GitHub.

**StudyPlanner — Your time. Your goals. Your progress.** 🚀
