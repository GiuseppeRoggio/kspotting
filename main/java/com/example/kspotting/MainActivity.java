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

import java.util.List;
import java.util.Locale;

// MainActivity estende AppCompatActivity per il supporto della compatibilità.
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AudioClassifier"; // Tag per il logging
    private static final int REQUEST_RECORD_AUDIO = 1337; // Codice di richiesta per il permesso audio

    private TextView displayTextView; // TextView per visualizzare i risultati
    private Button recordButton; // Bottone per avviare/fermare la registrazione

    private AudioClassificationHelper tfliteAudioHelper; // Istanza della classe helper
    private boolean isClassifierInitialized = false; // Stato di inizializzazione del classificatore

    // Metodo chiamato alla creazione dell'attività
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Imposta il layout dell'interfaccia utente

        // Inizializzazione degli elementi UI
        displayTextView = findViewById(R.id.display_text_view);
        recordButton = findViewById(R.id.record_button);

        // Listener per il click sul bottone di registrazione
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!isClassifierInitialized) {
                    Toast.makeText(MainActivity.this, "Classificatore in fase di caricamento, attendere.", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (tfliteAudioHelper.isRecording()) {
                    // Se sta registrando, ferma la registrazione
                    tfliteAudioHelper.stop();
                    recordButton.setText("Avvia Registrazione");
                    displayTextView.setText("Registrazione interrotta.");
                } else {
                    // Se non sta registrando, avvia la registrazione
                    tfliteAudioHelper.start();
                    recordButton.setText("Interrompi Registrazione");
                    displayTextView.setText("Classificazione in corso...");
                }
            }
        });

        // Controlla e richiede i permessi all'avvio dell'app
        checkPermissions();
    }

    // Metodo chiamato alla distruzione dell'attività
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Assicurati di fermare il classificatore per rilasciare le risorse
        if (tfliteAudioHelper != null) {
            tfliteAudioHelper.stop();
        }
    }

    // Controlla i permessi di registrazione audio
    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            // Permesso già concesso, inizializza il classificatore
            initAudioClassifier();
        } else {
            // Permesso non concesso, richiedilo all'utente
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO
            );
        }
    }

    // Callback per il risultato della richiesta di permessi
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permesso concesso, inizializza il classificatore
                initAudioClassifier();
            } else {
                // Permesso negato, mostra un messaggio all'utente
                Snackbar.make(
                                findViewById(android.R.id.content), // Usa il content view come genitore
                                "Il permesso di registrazione audio è necessario per questa app.",
                                Snackbar.LENGTH_INDEFINITE
                        )
                        .setAction("CONCEDI", new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                // Riprova a richiedere il permesso
                                ActivityCompat.requestPermissions(
                                        MainActivity.this,
                                        new String[]{Manifest.permission.RECORD_AUDIO},
                                        REQUEST_RECORD_AUDIO
                                );
                            }
                        })
                        .show();
            }
        }
    }

    // Inizializza l'AudioClassificationHelper e il modello TFLite
    private void initAudioClassifier() {
        // Inizializza l'helper in un thread di background per non bloccare la UI
        new Thread(new Runnable() {
            @Override
            public void run() {
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
                                        isClassifierInitialized = false; // Imposta lo stato a false in caso di errore
                                        recordButton.setText("Errore Inizializzazione");
                                        recordButton.setEnabled(false); // Disabilita il bottone in caso di errore
                                    }
                                });
                            }

                            @Override
                            public void onResults(List<Category> results, long inferenceTime) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        // Ordina i risultati per punteggio decrescente
                                        results.sort((o1, o2) -> Float.compare(o2.getScore(), o1.getScore()));

                                        // Costruisci la stringa dei risultati
                                        StringBuilder output = new StringBuilder();
                                        output.append(String.format(Locale.getDefault(), "Tempo di inferenza: %d ms\n\n", inferenceTime));
                                        int topResultsCount = Math.min(results.size(), 5); // Mostra solo i primi 5 risultati

                                        for (int i = 0; i < topResultsCount; i++) {
                                            Category category = results.get(i);
                                            output.append(String.format(Locale.getDefault(), "%s: %.2f%%\n",
                                                    category.getLabel(), category.getScore() * 100));
                                        }
                                        displayTextView.setText(output.toString());
                                    }
                                });
                            }
                        }
                );

                // Una volta inizializzato con successo, abilita il bottone di registrazione
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
        }).start(); // Avvia il thread
    }
}
