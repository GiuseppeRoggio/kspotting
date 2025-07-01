package com.example.kspotting;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.tensorflow.lite.support.label.Category;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class AudioClassificationService extends Service implements AudioClassificationHelper.ClassifierListener {

    private static final String TAG = "AudioClassificationService";
    private static final String CHANNEL_ID = "AudioClassifierChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int MAX_BACKGROUND_LOG_ENTRIES = 50;

    private static final String SENSITIVE_WORDS_CHANNEL_ID = "SensitiveWordsChannel";
    private static final int SENSITIVE_WORDS_NOTIFICATION_ID = 2;
    private static final List<String> SENSITIVE_WORDS = Arrays.asList("stop", "off");

    private static final float UI_BACKGROUND_LOG_THRESHOLD = 0.20f;
    private static final long UI_BACKGROUND_LOG_GROUPING_TIME_MS = 1000;

    public static final String ACTION_START_CLASSIFICATION = "com.example.kspotting.START_CLASSIFICATION";
    public static final String ACTION_STOP_CLASSIFICATION = "com.example.kspotting.STOP_CLASSIFICATION";
    public static final String ACTION_REQUEST_LOG_HISTORY = "com.example.kspotting.REQUEST_LOG_HISTORY";

    public static final String ACTION_CLASSIFICATION_RESULT = "com.example.kspotting.CLASSIFICATION_RESULT";
    public static final String ACTION_CLASSIFICATION_ERROR = "com.example.kspotting.CLASSIFICATION_ERROR";
    public static final String ACTION_SERVICE_INITIALIZED = "com.example.kspotting.SERVICE_INITIALIZED";
    public static final String ACTION_SERVICE_STOPPED = "com.example.kspotting.SERVICE_STOPPED";
    public static final String ACTION_LOG_HISTORY_RESPONSE = "com.example.kspotting.LOG_HISTORY_RESPONSE";

    public static final String EXTRA_LABELS = "extra_labels";
    public static final String EXTRA_SCORES = "extra_scores";
    public static final String EXTRA_INFERENCE_TIME = "extra_inference_time";
    public static final String EXTRA_ERROR_MESSAGE = "extra_error_message";
    public static final String EXTRA_LOG_HISTORY = "extra_log_history";

    private AudioClassificationHelper audioHelper;
    private LocalBroadcastManager localBroadcastManager;
    private LinkedList<ClassificationLogEntry> backgroundLogEntries;

    @Override
    public void onCreate() {
        super.onCreate();
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        createNotificationChannels();
        backgroundLogEntries = new LinkedList<>();
        Log.d(TAG, "Service onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "onStartCommand received action: " + action);

        if (ACTION_START_CLASSIFICATION.equals(action)) {
            startClassificationLogic();
        } else if (ACTION_STOP_CLASSIFICATION.equals(action)) {
            stopClassificationLogic();
        } else if (ACTION_REQUEST_LOG_HISTORY.equals(action)) {
            sendLogHistoryToActivity();
        }

        return START_STICKY;
    }

    private void startClassificationLogic() {
        startForeground(NOTIFICATION_ID, createMainNotification("Inizializzazione classificatore..."));

        if (audioHelper == null || !audioHelper.isClassifierInitialized()) {
            audioHelper = new AudioClassificationHelper(this, this);
        }

        if (audioHelper.isClassifierInitialized()) {
            audioHelper.start();
            updateMainNotification("Classificazione audio attiva");
            sendServiceInitializedBroadcast();
            Log.d(TAG, "Classificazione avviata con successo in service.");
        } else {
            String errorMsg = "Impossibile inizializzare il classificatore audio.";
            Log.e(TAG, errorMsg);
            onError(errorMsg);
        }
    }

    private void stopClassificationLogic() {
        Log.d(TAG, "Stopping classification logic.");
        if (audioHelper != null) {
            audioHelper.stop();
            audioHelper = null;
        }
        backgroundLogEntries.clear();
        sendServiceStoppedBroadcast();
        stopForeground(true);
        stopSelf();
        Log.d(TAG, "Classificazione interrotta e service terminato.");
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");
        if (audioHelper != null) {
            audioHelper.stop();
            audioHelper = null;
        }
        backgroundLogEntries.clear();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "Errore da AudioClassificationHelper: " + error);
        Intent errorIntent = new Intent(ACTION_CLASSIFICATION_ERROR);
        errorIntent.putExtra(EXTRA_ERROR_MESSAGE, error);
        localBroadcastManager.sendBroadcast(errorIntent);
        stopClassificationLogic();
    }

    @Override
    public void onResults(List<Category> results, long inferenceTime) {
        String[] labels = new String[results.size()];
        float[] scores = new float[results.size()];
        for (int i = 0; i < results.size(); i++) {
            Category category = results.get(i);
            labels[i] = category.getLabel();
            scores[i] = category.getScore();
        }
        Intent resultIntent = new Intent(ACTION_CLASSIFICATION_RESULT);
        resultIntent.putExtra(EXTRA_LABELS, labels);
        resultIntent.putExtra(EXTRA_SCORES, scores);
        resultIntent.putExtra(EXTRA_INFERENCE_TIME, inferenceTime);
        localBroadcastManager.sendBroadcast(resultIntent);

        if (!results.isEmpty()) {
            Category topResult = results.get(0);
            long now = System.currentTimeMillis();

            String normalizedTopLabel = topResult.getLabel().toLowerCase(Locale.ROOT).trim();
            Log.d(TAG, String.format(Locale.getDefault(),
                    "Top Result per parola sensibile - Etichetta: '%s', Confidenza: %.2f%%",
                    normalizedTopLabel, topResult.getScore() * 100));

            if (SENSITIVE_WORDS.contains(normalizedTopLabel) && topResult.getScore() >= UI_BACKGROUND_LOG_THRESHOLD) {
                showSensitiveWordNotification(normalizedTopLabel, topResult.getScore());
                Log.i(TAG, "Attivata notifica per parola sensibile: " + normalizedTopLabel);
            }

            if (topResult.getScore() >= UI_BACKGROUND_LOG_THRESHOLD) {
                boolean foundExistingEntry = false;
                if (!backgroundLogEntries.isEmpty()) {
                    ClassificationLogEntry lastEntry = backgroundLogEntries.getFirst();
                    if (lastEntry.label.toLowerCase(Locale.ROOT).trim().equals(normalizedTopLabel) &&
                            (now - lastEntry.timestamp < UI_BACKGROUND_LOG_GROUPING_TIME_MS)) {

                        if (topResult.getScore() > lastEntry.confidence) {
                            lastEntry.confidence = topResult.getScore();
                            lastEntry.timestamp = now;
                            backgroundLogEntries.removeFirst();
                            backgroundLogEntries.addFirst(lastEntry);
                        }
                        foundExistingEntry = true;
                    }
                }

                if (!foundExistingEntry) {
                    ClassificationLogEntry newEntry = new ClassificationLogEntry(
                            topResult.getLabel(),
                            topResult.getScore(),
                            now
                    );
                    backgroundLogEntries.addFirst(newEntry);

                    if (backgroundLogEntries.size() > MAX_BACKGROUND_LOG_ENTRIES) {
                        backgroundLogEntries.removeLast();
                    }
                }
            }
        }
    }

    private void sendLogHistoryToActivity() {
        Intent historyIntent = new Intent(ACTION_LOG_HISTORY_RESPONSE);
        historyIntent.putParcelableArrayListExtra(EXTRA_LOG_HISTORY, new ArrayList<Parcelable>(backgroundLogEntries));
        localBroadcastManager.sendBroadcast(historyIntent);
        Log.d(TAG, "Inviata cronologia dei log alla MainActivity. Numero di voci: " + backgroundLogEntries.size());
    }

    private void sendServiceInitializedBroadcast() {
        Intent intent = new Intent(ACTION_SERVICE_INITIALIZED);
        localBroadcastManager.sendBroadcast(intent);
    }

    private void sendServiceStoppedBroadcast() {
        Intent intent = new Intent(ACTION_SERVICE_STOPPED);
        localBroadcastManager.sendBroadcast(intent);
    }

    private void createNotificationChannels() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel mainChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Classificazione Audio",
                    NotificationManager.IMPORTANCE_LOW
            );
            mainChannel.setDescription("Canale per la classificazione audio in background");
            mainChannel.setSound(null, null);
            manager.createNotificationChannel(mainChannel);

            NotificationChannel sensitiveWordsChannel = new NotificationChannel(
                    SENSITIVE_WORDS_CHANNEL_ID,
                    "Avviso Parole Sensibili",
                    NotificationManager.IMPORTANCE_HIGH
            );
            sensitiveWordsChannel.setDescription("Notifiche per la rilevazione di parole sensibili.");
            sensitiveWordsChannel.enableVibration(true);
            sensitiveWordsChannel.setVibrationPattern(new long[]{0, 500, 200, 500});
            manager.createNotificationChannel(sensitiveWordsChannel);
        }
    }

    private Notification createMainNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Intent stopIntent = new Intent(this, AudioClassificationService.class);
        stopIntent.setAction(ACTION_STOP_CLASSIFICATION);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Classificazione Audio Attiva")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_media_play)
                // CORREZIONE QUI: Usare un'icona generica o quella dell'app per l'azione
                .addAction(R.mipmap.ic_launcher, "Stop", stopPendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void updateMainNotification(String contentText) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createMainNotification(contentText));
        }
    }

    private void showSensitiveWordNotification(String word, float confidence) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        String notificationText = String.format(Locale.getDefault(),
                "ATTENZIONE: PAROLA SENSIBILE RILEVATA - '%s' - %.2f%%", word, confidence * 100);

        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, SENSITIVE_WORDS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Avviso Parola Sensibile")
                .setContentText(notificationText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        manager.notify(SENSITIVE_WORDS_NOTIFICATION_ID, builder.build());
        Log.i(TAG, "Notifica parola sensibile: " + word + " con confidenza " + confidence * 100 + "%");
    }
}
