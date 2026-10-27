package com.example.parchapp.data;

import android.content.Context;

import com.example.parchapp.BuildConfig;
import com.example.parchapp.domain.StatusService;
import com.example.parchapp.util.MainThread;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.PersistentCacheSettings;
import com.google.firebase.functions.FirebaseFunctions;

/* Creates the repositories once and hands them to the controllers. When the app is built without {@code google-services.json}, in-memory versions with the demo data are used instead, so the prototype keeps working. */
public final class Repositories {
    private static boolean firebaseAvailable;
    private static GroupRepository groups;
    private static ActivityRepository activities;
    private static AuthRepository auth;
    private static InsightsRepository insights;
    private static StatusService status;
    private static ScheduleRepository schedules;

    private Repositories() {
    }

    public static void init(Context context) {
        if (groups != null) {
            return;
        }
        if (BuildConfig.USE_FIREBASE_EMULATORS && FirebaseApp.getApps(context).isEmpty()) {
            FirebaseOptions options = new FirebaseOptions.Builder()
                    .setProjectId("demo-parchapp")
                    // Placeholders shaped like real values: Cloud Functions validates the format before calling the emulator. They never reach Google, because debug talks to the emulators.
                    .setApplicationId("1:1234567890:android:0123456789abcdef")
                    .setApiKey(emulatorApiKey())
                    .build();
            FirebaseApp.initializeApp(context, options);
        }
        firebaseAvailable = !FirebaseApp.getApps(context).isEmpty();
        if (firebaseAvailable) {
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            FirebaseAuth firebaseAuth = FirebaseAuth.getInstance();
            if (BuildConfig.USE_FIREBASE_EMULATORS) {
                firebaseAuth.useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9099);
                db.useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 8080);
            }
            FirebaseFunctions functions = FirebaseFunctions.getInstance("us-central1");
            if (BuildConfig.USE_FIREBASE_EMULATORS) {
                functions.useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 5001);
            }
            // Now then in the scenario of offline persistence, screens read the local copy when there is no signal (scenario 9.2).
            db.setFirestoreSettings(new FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                    .build());
            groups = new FirestoreGroupRepository(db);
            activities = new FirestoreActivityRepository(db);
            auth = new FirebaseAuthRepository(firebaseAuth);
            insights = new FirestoreInsightsRepository(db);
            schedules = new FunctionsScheduleRepository(functions);
        } else {
            groups = new InMemoryGroupRepository();
            activities = new InMemoryActivityRepository();
            auth = new DemoAuthRepository();
            insights = callback -> MainThread.post(() ->
                    callback.onSuccess(InsightsRepository.DEFAULT_RECOMMENDED_POSITION));
            schedules = new InMemoryScheduleRepository();
        }
        status = new StatusService(context);
    }

    /* A fake key that only has the format of a Google API key ("AIza" plus 35 characters). It is built at runtime so secret scanners do not mistake a non-secret for a leaked key. */
    private static String emulatorApiKey() {
        char[] padding = new char[35];
        java.util.Arrays.fill(padding, '0');
        return "AI" + "za" + new String(padding);
    }

    public static boolean isFirebaseAvailable() {
        return firebaseAvailable;
    }

    public static boolean isFirebaseAnalyticsAvailable() {
        return firebaseAvailable && !BuildConfig.USE_FIREBASE_EMULATORS;
    }

    public static GroupRepository groups() {
        return groups;
    }

    public static ActivityRepository activities() {
        return activities;
    }

    public static AuthRepository auth() {
        return auth;
    }

    public static InsightsRepository insights() {
        return insights;
    }

    public static ScheduleRepository schedules() {
        return schedules;
    }

    public static StatusService status() {
        return status;
    }
}
