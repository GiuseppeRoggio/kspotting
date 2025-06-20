package com.example.kspotting; // Nome del package del tuo progetto

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import org.tensorflow.lite.support.label.Category;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AudioClassifier";
    private static final int REQUEST_RECORD_AUDIO = 1337;

    private static final float UI_DISPLAY_WORD_THRESHOLD = 0.90f;
    private static final float UI_RECENT_LOG_THRESHOLD = 0.20f;
    private static final int MAX_LOG_ENTRIES = 10;
    private static final long UI_DEBOUNCE_DELAY_MS = 750;
    private static final long UI_SILENCE_DEBOUNCE_DELAY_MS = 1500;
    private static final long UI_RECENT_LOG_GROUPING_TIME_MS = 1000;

    private static final List<String> KNOWN_COMMANDS = Arrays.asList(
            "down", "go", "left", "off", "on", "right", "stop", "up"
    );

    private TextView displayTextView;
    private TextView recentInferencesTextView;
    private TextView knownCommandsListTextView;
    private Button recordButton;

    private AudioClassificationHelper tfliteAudioHelper;
    private boolean isClassifierInitialized = false;

    private LinkedList<RecentLogEntry> recentLogEntriesList;

    private String lastDisplayedCommandLabel = "";
    private float lastDisplayedConfidence = 0.0f;
    private long lastUIUpdateTime = 0;

    private static class RecentLogEntry {
        String label;
        float confidence;
        long timestamp;

        RecentLogEntry(String label, float confidence, long timestamp) {
            this.label = label;
            this.confidence = confidence;
            this.timestamp = timestamp;
        }

        public String formatForDisplay() {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            String currentTimeFormatted = sdf.format(new Date(timestamp));
            String displayLabel = label;
            if (label.equals("_background_noise_")) displayLabel = "Background";
            if (label.equals("silence")) displayLabel = "Silenzio";

            return String.format(Locale.getDefault(), "%s - %s: %.2f%%\n",
                    currentTimeFormatted, displayLabel, confidence * 100);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        displayTextView = findViewById(R.id.display_text_view);
        recentInferencesTextView = findViewById(R.id.recent_inferences_text_view);
        knownCommandsListTextView = findViewById(R.id.known_commands_list_text_view);
        recordButton = findViewById(R.id.record_button);

        recentLogEntriesList = new LinkedList<>();

        recentInferencesTextView.setText(String.format(Locale.getDefault(), "Log recenti (UI > %.0f%% o background/silence):\n", UI_DISPLAY_WORD_THRESHOLD * 100));
        knownCommandsListTextView.setText("Comandi attesi: " + String.join(", ", KNOWN_COMMANDS));

        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!isClassifierInitialized) {
                    initAudioClassifier();
                } else {
                    if (tfliteAudioHelper.isRecording()) {
                        tfliteAudioHelper.stop();
                        isClassifierInitialized = false;
                        recordButton.setText("Avvia Registrazione");
                        displayTextView.setText("Registrazione interrotta.");
                        recentLogEntriesList.clear();
                        recentInferencesTextView.setText(String.format(Locale.getDefault(), "Log recenti (UI > %.0f%% o background/silence):\n", UI_DISPLAY_WORD_THRESHOLD * 100));
                        resetUIDebounceState();
                    } else {
                        tfliteAudioHelper.start();
                        recordButton.setText("Interrompi Registrazione");
                        displayTextView.setText("Classificazione in corso...");
                        recentLogEntriesList.clear();
                        recentInferencesTextView.setText(String.format(Locale.getDefault(), "Log recenti (UI > %.0f%% o background/silence):\n", UI_DISPLAY_WORD_THRESHOLD * 100));
                        resetUIDebounceState();
                    }
                }
            }
        });

        checkPermissions();
    }

    private void resetUIDebounceState() {
        lastDisplayedCommandLabel = "";
        lastDisplayedConfidence = 0.0f;
        lastUIUpdateTime = 0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tfliteAudioHelper != null) {
            tfliteAudioHelper.stop();
        }
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            initAudioClassifier();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initAudioClassifier();
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
                recordButton.setEnabled(false);
            }
        }
    }

    private void initAudioClassifier() {
        recordButton.setEnabled(false);
        displayTextView.setText("Inizializzazione classificatore...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                if (tfliteAudioHelper != null && tfliteAudioHelper.isClassifierInitialized()) {
                    tfliteAudioHelper.stop();
                }

                tfliteAudioHelper = new AudioClassificationHelper(
                        MainActivity.this,
                        new AudioClassificationHelper.ClassifierListener() {
                            @Override
                            public void onError(String error) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
                                        Log.e(TAG, error);
                                        displayTextView.setText("Errore: " + error);
                                        isClassifierInitialized = false;
                                        recordButton.setText("Errore Inizializzazione");
                                        recordButton.setEnabled(false);
                                    }
                                });
                            }

                            @Override
                            public void onResults(List<Category> results, long inferenceTime) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        results.sort((o1, o2) -> Float.compare(o2.getScore(), o1.getScore()));

                                        Category topResult = null;
                                        if (!results.isEmpty()) {
                                            topResult = results.get(0);
                                        }

                                        StringBuilder logcatOutput = new StringBuilder();
                                        logcatOutput.append("--- Nuova Inferenza (Tempo: ").append(inferenceTime).append("ms) ---\n");
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
                                            if (!currentCommandForUI.equals(lastDisplayedCommandLabel)) {
                                                shouldUpdateDisplay = true;
                                            } else {
                                                if (currentConfidenceForUI > lastDisplayedConfidence) {
                                                    shouldUpdateDisplay = true;
                                                }
                                            }
                                        } else {
                                            if (lastDisplayedCommandLabel.equals("Silenzio o Nessun comando valido")) {
                                                if (currentTime - lastUIUpdateTime > UI_DEBOUNCE_DELAY_MS) {
                                                    shouldUpdateDisplay = true;
                                                }
                                            } else {
                                                if (currentTime - lastUIUpdateTime > UI_SILENCE_DEBOUNCE_DELAY_MS) {
                                                    shouldUpdateDisplay = true;
                                                }
                                            }
                                        }

                                        if (shouldUpdateDisplay) {
                                            if (isActualCommandRecognized) {
                                                displayTextView.setText(String.format(Locale.getDefault(), "%s: %.2f%%\nTempo di inferenza: %d ms",
                                                        currentCommandForUI, currentConfidenceForUI * 100, inferenceTime));
                                            } else {
                                                displayTextView.setText(String.format(Locale.getDefault(), "%s\nTempo di inferenza: %d ms",
                                                        currentCommandForUI, inferenceTime));
                                            }
                                            lastDisplayedCommandLabel = currentCommandForUI;
                                            lastDisplayedConfidence = currentConfidenceForUI;
                                            lastUIUpdateTime = currentTime;
                                        }

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
                                            String displayLabel = labelToLog;
                                            if (labelToLog.equals("_background_noise_")) displayLabel = "Background";
                                            if (labelToLog.equals("silence")) displayLabel = "Silenzio";

                                            boolean foundExistingEntry = false;
                                            for (int i = recentLogEntriesList.size() - 1; i >= 0; i--) {
                                                RecentLogEntry entry = recentLogEntriesList.get(i);
                                                if (entry.label.equals(labelToLog) &&
                                                        (now - entry.timestamp < UI_RECENT_LOG_GROUPING_TIME_MS)) {
                                                    if (confidenceToLog > entry.confidence) {
                                                        entry.confidence = confidenceToLog;
                                                        entry.timestamp = now;
                                                        if (i != 0) {
                                                            recentLogEntriesList.remove(i);
                                                            recentLogEntriesList.addFirst(entry);
                                                        }
                                                    }
                                                    foundExistingEntry = true;
                                                    break;
                                                }
                                            }

                                            if (!foundExistingEntry) {
                                                RecentLogEntry newEntry = new RecentLogEntry(labelToLog, confidenceToLog, now);
                                                recentLogEntriesList.addFirst(newEntry);
                                                if (recentLogEntriesList.size() > MAX_LOG_ENTRIES) {
                                                    recentLogEntriesList.removeLast();
                                                }
                                            }

                                            StringBuilder logBuilder = new StringBuilder(String.format(Locale.getDefault(), "Log recenti (UI > %.0f%% o background/silence):\n\n", UI_DISPLAY_WORD_THRESHOLD * 100));
                                            for (RecentLogEntry entry : recentLogEntriesList) {
                                                logBuilder.append(entry.formatForDisplay());
                                            }
                                            recentInferencesTextView.setText(logBuilder.toString());
                                        }
                                    }
                                });
                            }
                        }
                );

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        isClassifierInitialized = true;
                        recordButton.setEnabled(true);
                        recordButton.setText("Avvia Registrazione");
                        displayTextView.setText("Classificatore Speech Command pronto.\nPremi per avviare la classificazione.");
                        Log.i(TAG, "Classificatore TFLite inizializzato con successo.");
                    }
                });
            }
        }).start();
    }
}
