package com.example.kspotting;

import android.Manifest;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface; // Import per il grassetto
import android.os.Bundle;
import android.os.Build;
import android.os.Parcelable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan; // Import per dimensione testo
import android.text.style.StyleSpan; // Import per stile grassetto
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView; // Import per ScrollView
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.snackbar.Snackbar;

import org.tensorflow.lite.support.label.Category;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AudioClassifier";
    private static final int REQUEST_RECORD_AUDIO = 1337;
    private static final int REQUEST_POST_NOTIFICATIONS = 1338;

    private static final float UI_DISPLAY_WORD_THRESHOLD = 0.90f;
    private static final float UI_RECENT_LOG_THRESHOLD = 0.20f;
    private static final int MAX_LOG_ENTRIES = 10;
    private static final long UI_DEBOUNCE_DELAY_MS = 750;
    private static final long UI_SILENCE_DEBOUNCE_DELAY_MS = 1500;
    private static final long UI_RECENT_LOG_GROUPING_TIME_MS = 1000;

    private static final List<String> KNOWN_COMMANDS = Arrays.asList(
            "down", "go", "left", "off", "on", "right", "stop", "up"
    );
    private static final List<String> SENSITIVE_WORDS = Arrays.asList("stop", "off");

    private TextView displayTextView;
    private TextView recentInferencesTextView;
    private TextView knownCommandsListTextView;
    private Button recordButton;
    private ScrollView logScrollView; // Dichiarazione della ScrollView

    private boolean isAudioServiceRunning = false;

    private String lastDisplayedCommandLabel = "";
    private float lastDisplayedConfidence = 0.0f;
    private long lastUIUpdateTime = 0;

    private LinkedList<RecentLogEntry> recentLogEntriesList;

    private final BroadcastReceiver classificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Broadcast received: " + action);

            if (AudioClassificationService.ACTION_CLASSIFICATION_RESULT.equals(action)) {
                String[] labels = intent.getStringArrayExtra(AudioClassificationService.EXTRA_LABELS);
                float[] scores = intent.getFloatArrayExtra(AudioClassificationService.EXTRA_SCORES);
                long inferenceTime = intent.getLongExtra(AudioClassificationService.EXTRA_INFERENCE_TIME, 0);

                if (labels != null && scores != null && labels.length == scores.length) {
                    List<Category> results = new ArrayList<>();
                    for (int i = 0; i < labels.length; i++) {
                        results.add(new Category(labels[i], scores[i]));
                    }
                    handleClassificationResults(results, inferenceTime);
                }

            } else if (AudioClassificationService.ACTION_CLASSIFICATION_ERROR.equals(action)) {
                String errorMessage = intent.getStringExtra(AudioClassificationService.EXTRA_ERROR_MESSAGE);
                handleClassificationError(errorMessage);

            } else if (AudioClassificationService.ACTION_SERVICE_INITIALIZED.equals(action)) {
                handleServiceInitialized();

            } else if (AudioClassificationService.ACTION_SERVICE_STOPPED.equals(action)) {
                handleServiceStopped();
            } else if (AudioClassificationService.ACTION_LOG_HISTORY_RESPONSE.equals(action)) {
                ArrayList<Parcelable> parcelableLogHistory = intent.getParcelableArrayListExtra(AudioClassificationService.EXTRA_LOG_HISTORY);
                if (parcelableLogHistory != null) {
                    recentLogEntriesList.clear();
                    for (Parcelable p : parcelableLogHistory) {
                        if (p instanceof ClassificationLogEntry) {
                            ClassificationLogEntry logEntry = (ClassificationLogEntry) p;
                            recentLogEntriesList.add(new RecentLogEntry(logEntry.label, logEntry.confidence, logEntry.timestamp));
                        }
                    }
                    recentLogEntriesList.sort((o1, o2) -> Long.compare(o2.timestamp, o1.timestamp));

                    updateRecentInferencesTextView();
                    Log.d(TAG, "Cronologia log ricevuta e aggiornata nella UI. Voci: " + recentLogEntriesList.size());
                }
            }
        }
    };

    private static class RecentLogEntry {
        String label;
        float confidence;
        long timestamp;

        RecentLogEntry(String label, float confidence, long timestamp) {
            this.label = label;
            this.confidence = confidence;
            this.timestamp = timestamp;
        }

        public SpannableString formatForDisplay() {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            String currentTimeFormatted = sdf.format(new Date(timestamp));
            String displayLabel = label;
            if (label.equals("_background_noise_")) displayLabel = "Rumore di Fondo";
            if (label.equals("silence")) displayLabel = "Silenzio";

            String formattedText;
            String normalizedLabel = label.toLowerCase(Locale.ROOT).trim();

            if (SENSITIVE_WORDS.contains(normalizedLabel)) {
                // Formattazione speciale per parole sensibili
                formattedText = String.format(Locale.getDefault(),
                        "%s - ATTENZIONE: RILEVATA PAROLA SENSIBILE - %s: %.2f%%\n",
                        currentTimeFormatted, displayLabel.toUpperCase(Locale.ROOT), confidence * 100);
            } else {
                // Formattazione standard
                formattedText = String.format(Locale.getDefault(),
                        "%s - %s: %.2f%%\n",
                        currentTimeFormatted, displayLabel, confidence * 100);
            }

            SpannableString spannableString = new SpannableString(formattedText);

            if (SENSITIVE_WORDS.contains(normalizedLabel)) {
                // Colore di sfondo per l'intera riga della parola sensibile
                // Ho scelto un giallo molto chiaro per un buon contrasto
                spannableString.setSpan(new BackgroundColorSpan(Color.parseColor("#FFFDD0")), // Giallo pallido
                        0,
                        formattedText.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                // Colore del testo rosso, grassetto e leggermente più grande per la parola chiave
                String wordToHighlight = displayLabel.toUpperCase(Locale.ROOT);
                int startIndex = formattedText.indexOf(wordToHighlight);
                if (startIndex != -1) {
                    spannableString.setSpan(new ForegroundColorSpan(Color.RED),
                            startIndex,
                            startIndex + wordToHighlight.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spannableString.setSpan(new StyleSpan(Typeface.BOLD), // Grassetto
                            startIndex,
                            startIndex + wordToHighlight.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spannableString.setSpan(new RelativeSizeSpan(1.1f), // Leggermente più grande
                            startIndex,
                            startIndex + wordToHighlight.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            return spannableString;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        setupClickListeners();
    }

    private void initializeViews() {
        displayTextView = findViewById(R.id.display_text_view);
        recentInferencesTextView = findViewById(R.id.recent_inferences_text_view);
        knownCommandsListTextView = findViewById(R.id.known_commands_list_text_view);
        recordButton = findViewById(R.id.record_button);
        logScrollView = findViewById(R.id.log_scroll_view); // Inizializzazione della ScrollView

        recentLogEntriesList = new LinkedList<>();

        updateRecentInferencesTextView();
        knownCommandsListTextView.setText("Comandi attesi: " + String.join(", ", KNOWN_COMMANDS));

        displayTextView.setText("Classificatore Speech Command pronto.");
        recordButton.setEnabled(false);
    }

    private void setupClickListeners() {
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isAudioServiceRunning) {
                    stopAudioClassificationService();
                } else {
                    startAudioClassificationService();
                }
            }
        });
    }

    private void resetUIDebounceState() {
        lastDisplayedCommandLabel = "";
        lastDisplayedConfidence = 0.0f;
        lastUIUpdateTime = 0;
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioClassificationService.ACTION_CLASSIFICATION_RESULT);
        filter.addAction(AudioClassificationService.ACTION_CLASSIFICATION_ERROR);
        filter.addAction(AudioClassificationService.ACTION_SERVICE_INITIALIZED);
        filter.addAction(AudioClassificationService.ACTION_SERVICE_STOPPED);
        filter.addAction(AudioClassificationService.ACTION_LOG_HISTORY_RESPONSE);
        LocalBroadcastManager.getInstance(this).registerReceiver(classificationReceiver, filter);

        checkAndRequestPermissions();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(classificationReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_RECORD_AUDIO
            );
        } else {
            updateServiceStateBasedOnRunningServices();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean allPermissionsGranted = true;

        if (requestCode == REQUEST_RECORD_AUDIO) {
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.RECORD_AUDIO)) {
                    if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "Permesso microfono concesso.", Toast.LENGTH_SHORT).show();
                    } else {
                        Snackbar.make(
                                        findViewById(android.R.id.content),
                                        "Il permesso di registrazione audio è necessario per questa app.",
                                        Snackbar.LENGTH_INDEFINITE
                                )
                                .setAction("CONCEDI", new View.OnClickListener() {
                                    @Override
                                    public void onClick(View view) {
                                        ActivityCompat.requestPermissions(
                                                MainActivity.this,
                                                new String[]{Manifest.permission.RECORD_AUDIO},
                                                REQUEST_RECORD_AUDIO
                                        );
                                    }
                                })
                                .show();
                        allPermissionsGranted = false;
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        permissions[i].equals(Manifest.permission.POST_NOTIFICATIONS)) {
                    if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "Permesso notifiche concesso.", Toast.LENGTH_SHORT).show();
                    } else {
                        Snackbar.make(
                                        findViewById(android.R.id.content),
                                        "Il permesso per le notifiche è raccomandato per gli avvisi di parole sensibili.",
                                        Snackbar.LENGTH_LONG
                                )
                                .setAction("IMPOSTAZIONI", new View.OnClickListener() {
                                    @Override
                                    public void onClick(View view) {
                                        Intent intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                                        startActivity(intent);
                                    }
                                })
                                .show();
                    }
                }
            }

            if (allPermissionsGranted) {
                updateServiceStateBasedOnRunningServices();
            } else {
                recordButton.setEnabled(false);
                displayTextView.setText("Permessi non concessi.");
                isAudioServiceRunning = false;
            }
        }
    }

    private boolean isMyServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateServiceStateBasedOnRunningServices() {
        isAudioServiceRunning = isMyServiceRunning(AudioClassificationService.class);

        runOnUiThread(() -> {
            if (isAudioServiceRunning) {
                recordButton.setText("Interrompi Classificazione");
                displayTextView.setText("Classificazione audio attiva in background");
                requestLogHistoryFromService();
            } else {
                recordButton.setText("Avvia Classificazione");
                displayTextView.setText("Classificatore Speech Command pronto.");
            }
            recordButton.setEnabled(true);

            if (!isAudioServiceRunning) {
                recentLogEntriesList.clear();
                updateRecentInferencesTextView();
                resetUIDebounceState();
            }
        });
    }

    private void requestLogHistoryFromService() {
        Log.d(TAG, "Richiesta cronologia log al servizio.");
        Intent requestHistoryIntent = new Intent(this, AudioClassificationService.class);
        requestHistoryIntent.setAction(AudioClassificationService.ACTION_REQUEST_LOG_HISTORY);
        startService(requestHistoryIntent);
    }

    private void startAudioClassificationService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Snackbar.make(
                    findViewById(android.R.id.content),
                    "Il permesso microfono è necessario per avviare la classificazione.",
                    Snackbar.LENGTH_LONG
            ).show();
            checkAndRequestPermissions();
            return;
        }

        Intent serviceIntent = new Intent(this, AudioClassificationService.class);
        serviceIntent.setAction(AudioClassificationService.ACTION_START_CLASSIFICATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent);
        } else {
            startService(serviceIntent);
        }

        recordButton.setEnabled(false);
        displayTextView.setText("Avvio classificazione in background...");
        Log.d(TAG, "Richiesto avvio del service di classificazione");
    }

    private void stopAudioClassificationService() {
        Intent serviceIntent = new Intent(this, AudioClassificationService.class);
        serviceIntent.setAction(AudioClassificationService.ACTION_STOP_CLASSIFICATION);
        startService(serviceIntent);

        recordButton.setEnabled(false);
        displayTextView.setText("Richiesta interruzione classificazione...");
        Log.d(TAG, "Richiesto stop del service di classificazione");
    }

    private void handleClassificationError(String error) {
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "Errore classificazione: " + error, Toast.LENGTH_LONG).show();
            Log.e(TAG, "Classification error: " + error);
            displayTextView.setText("Errore: " + error);
            isAudioServiceRunning = false;
            recordButton.setText("Avvia Classificazione");
            recordButton.setEnabled(true);
            resetUIDebounceState();
            recentLogEntriesList.clear();
            updateRecentInferencesTextView();
        });
    }

    private void handleServiceInitialized() {
        runOnUiThread(() -> {
            isAudioServiceRunning = true;
            recordButton.setText("Interrompi Classificazione");
            recordButton.setEnabled(true);
            displayTextView.setText("Classificazione audio attiva in background.");
            resetUIDebounceState();
            recentLogEntriesList.clear();
            updateRecentInferencesTextView();
            Log.i(TAG, "Servizio inizializzato e in esecuzione.");
            requestLogHistoryFromService();
        });
    }

    private void handleServiceStopped() {
        runOnUiThread(() -> {
            isAudioServiceRunning = false;
            recordButton.setText("Avvia Classificazione");
            recordButton.setEnabled(true);
            displayTextView.setText("Classificazione interrotta.");
            resetUIDebounceState();
            recentLogEntriesList.clear();
            updateRecentInferencesTextView();
            Log.i(TAG, "Servizio fermato.");
        });
    }

    private void handleClassificationResults(List<Category> results, long inferenceTime) {
        runOnUiThread(() -> {
            results.sort((o1, o2) -> Float.compare(o2.getScore(), o1.getScore()));

            Category topResult = null;
            if (!results.isEmpty()) {
                topResult = results.get(0);
            }

            StringBuilder logcatOutput = new StringBuilder();
            logcatOutput.append("--- Nuova Inferenza dal Service (Tempo: ").append(inferenceTime).append("ms) ---\n");
            for (Category category : results) {
                String scoreStr;
                if (Float.isNaN(category.getScore())) {
                    scoreStr = "NaN%";
                } else {
                    scoreStr = String.format(Locale.getDefault(), "%.2f%%", category.getScore() * 100);
                }
                logcatOutput.append(String.format(Locale.getDefault(), "  %s: %s\n",
                        category.getLabel(), scoreStr));
            }
            Log.i(TAG, logcatOutput.toString());

            String currentCommandForUI = "Silenzio o Nessun comando valido";
            float currentConfidenceForUI = 0.0f;
            boolean isActualCommandRecognized = false;

            if (topResult != null && topResult.getScore() >= UI_DISPLAY_WORD_THRESHOLD &&
                    !topResult.getLabel().equals("_background_noise_") &&
                    !topResult.getLabel().equals("silence")) {
                currentCommandForUI = topResult.getLabel();
                currentConfidenceForUI = topResult.getScore();
                isActualCommandRecognized = true;
            }

            long currentTime = System.currentTimeMillis();
            boolean shouldUpdateDisplay = false;

            if (isActualCommandRecognized) {
                if (!currentCommandForUI.equals(lastDisplayedCommandLabel) || currentConfidenceForUI > lastDisplayedConfidence + 0.05f) {
                    shouldUpdateDisplay = true;
                }
            } else {
                if (!lastDisplayedCommandLabel.equals("Silenzio o Nessun comando valido")) {
                    shouldUpdateDisplay = true;
                } else if (currentTime - lastUIUpdateTime > UI_SILENCE_DEBOUNCE_DELAY_MS) {
                    shouldUpdateDisplay = true;
                }
            }

            if (shouldUpdateDisplay) {
                if (isActualCommandRecognized) {
                    displayTextView.setText(String.format(Locale.getDefault(),
                            "%s: %.2f%%\nTempo di inferenza: %d ms\n(Servizio Background)",
                            currentCommandForUI, currentConfidenceForUI * 100, inferenceTime));
                } else {
                    displayTextView.setText(String.format(Locale.getDefault(),
                            "%s\nTempo di inferenza: %d ms\n(Servizio Background)",
                            currentCommandForUI, inferenceTime));
                }
                lastDisplayedCommandLabel = currentCommandForUI;
                lastDisplayedConfidence = currentConfidenceForUI;
                lastUIUpdateTime = currentTime;
            }

            updateRecentLog(topResult, isActualCommandRecognized);
        });
    }

    private void updateRecentLog(Category topResult, boolean isActualCommandRecognized) {
        String labelToLog = null;
        float confidenceToLog = 0.0f;
        boolean shouldLogToRecent = false;
        long now = System.currentTimeMillis();

        if (isActualCommandRecognized) {
            labelToLog = topResult.getLabel();
            confidenceToLog = topResult.getScore();
            shouldLogToRecent = true;
        } else if (topResult != null &&
                (topResult.getLabel().equals("_background_noise_") || topResult.getLabel().equals("silence")) &&
                topResult.getScore() >= UI_RECENT_LOG_THRESHOLD) {
            labelToLog = topResult.getLabel();
            confidenceToLog = topResult.getScore();
            shouldLogToRecent = true;
        }

        if (shouldLogToRecent) {
            boolean foundExistingEntry = false;
            if (!recentLogEntriesList.isEmpty()) {
                RecentLogEntry lastEntry = recentLogEntriesList.getFirst();
                if (lastEntry.label.equals(labelToLog) && (now - lastEntry.timestamp < UI_RECENT_LOG_GROUPING_TIME_MS)) {
                    if (confidenceToLog > lastEntry.confidence) {
                        lastEntry.confidence = confidenceToLog;
                        lastEntry.timestamp = now;
                    }
                    foundExistingEntry = true;
                }
            }

            if (!foundExistingEntry) {
                RecentLogEntry newEntry = new RecentLogEntry(labelToLog, confidenceToLog, now);
                recentLogEntriesList.addFirst(newEntry);
                if (recentLogEntriesList.size() > MAX_LOG_ENTRIES) {
                    recentLogEntriesList.removeLast();
                }
            }
            updateRecentInferencesTextView();
        }
    }

    private void updateRecentInferencesTextView() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.getDefault(),
                "Log recenti (UI > %.0f%% o background/silence):\n\n", UI_DISPLAY_WORD_THRESHOLD * 100));

        for (RecentLogEntry entry : recentLogEntriesList) {
            sb.append(entry.formatForDisplay());
        }
        recentInferencesTextView.setText(sb, TextView.BufferType.SPANNABLE);
        // Assicurati che lo scroll sia sempre in basso per vedere gli ultimi log
        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
    }
}
