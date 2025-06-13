package com.example.kspotting; // Nome del package del tuo progetto

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

// Rimosse importazioni relative alla GPU, non più necessarie
// import org.tensorflow.lite.gpu.CompatibilityList;
// import org.tensorflow.lite.gpu.GpuDelegate;

import org.tensorflow.lite.support.audio.TensorAudio;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.audio.classifier.AudioClassifier;
import org.tensorflow.lite.task.audio.classifier.AudioClassifier.AudioClassifierOptions;
import org.tensorflow.lite.task.core.BaseOptions;
import org.tensorflow.lite.task.audio.classifier.Classifications; // Import corretto per Classifications

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

// Helper per la classificazione audio con TensorFlow Lite
public class AudioClassificationHelper {

    private static final String TAG = "AudioClassifierHelper"; // Tag per il logging
    private static final String MODEL_NAME = "speech_commands.tflite"; // Nome del modello TFLite
    private static final long CLASSIFIER_INTERVAL_MS = 500; // Intervallo di inferenza in ms

    private final Context context;
    private final ClassifierListener classifierListener; // Listener per i risultati
    private AudioClassifier classifier; // L'interprete del modello TFLite
    private TensorAudio tensorAudio; // Buffer per i dati audio
    private AudioRecord record; // Oggetto per la registrazione audio
    private ScheduledExecutorService executorService; // Executor per il thread di inferenza
    private AtomicBoolean isRecording = new AtomicBoolean(false); // Stato della registrazione
    private AtomicBoolean isClassifierInitialized = new AtomicBoolean(false); // Stato di inizializzazione del classificatore

    // Interfaccia listener per comunicare i risultati alla UI
    public interface ClassifierListener {
        void onError(String error);
        void onResults(List<Category> results, long inferenceTime);
    }

    // Costruttore
    public AudioClassificationHelper(Context context, ClassifierListener listener) {
        this.context = context;
        this.classifierListener = listener;
        initClassifier(); // Inizializza il classificatore all'istanziazione
    }

    // Inizializza il classificatore TFLite
    private void initClassifier() {
        if (isClassifierInitialized.get()) {
            return; // Già inizializzato
        }

        try {
            // Crea le opzioni di base per il classificatore
            BaseOptions.Builder baseOptionsBuilder = BaseOptions.builder()
                    .setNumThreads(2); // Numero di thread per l'inferenza

            // Rimosso il blocco di configurazione della GPU per semplicità
            // Log.d(TAG, "Utilizzo della CPU per la classificazione audio.");
            // Non è necessario aggiungere una riga .useCpu() esplicitamente, è il default.


            // Crea le opzioni per il classificatore audio
            AudioClassifierOptions options =
                    AudioClassifierOptions.builder()
                            .setBaseOptions(baseOptionsBuilder.build())
                            .setMaxResults(5) // Imposta il numero massimo di risultati direttamente qui
                            .build();

            // Crea l'istanza del classificatore dal file del modello
            classifier = AudioClassifier.createFromFileAndOptions(context, MODEL_NAME, options);

            // Inizializza TensorAudio con le specifiche audio richieste dal classificatore
            tensorAudio = classifier.createInputTensorAudio();

            // Configura AudioRecord per l'acquisizione audio
            // Utilizza i parametri audio dal classificatore per la frequenza di campionamento e il formato
            int sampleRate = classifier.getRequiredTensorAudioFormat().getSampleRate();
            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

            int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Errore nella dimensione minima del buffer audio");
                classifierListener.onError("Errore nella configurazione dell'audio.");
                return;
            }

            record = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBufferSize
            );

            // Controlla lo stato di inizializzazione dell'AudioRecord
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord non inizializzato correttamente.");
                classifierListener.onError("Microfono non disponibile o inizializzazione fallita.");
                record.release(); // Rilascia le risorse se l'inizializzazione fallisce
                return;
            }

            isClassifierInitialized.set(true);
            Log.i(TAG, "Classificatore TFLite e AudioRecord inizializzati con successo.");

        } catch (IOException e) {
            Log.e(TAG, "Errore nel caricamento del modello TFLite: " + e.getMessage());
            classifierListener.onError("Errore nel caricamento del modello: " + e.getMessage());
        } catch (RuntimeException e) {
            // Cattura errori comuni durante l'inizializzazione del classificatore
            Log.e(TAG, "Errore in fase di runtime durante l'inizializzazione: " + e.getMessage());
            classifierListener.onError("Errore di runtime nell'inizializzazione: " + e.getMessage() + ". Assicurati che il modello sia valido.");
        }
    }

    // Avvia la registrazione audio e la classificazione in un thread separato
    public void start() {
        if (!isClassifierInitialized.get()) {
            classifierListener.onError("Classificatore non inizializzato.");
            return;
        }

        if (isRecording.get()) {
            Log.d(TAG, "La registrazione è già in corso.");
            return;
        }

        isRecording.set(true);
        record.startRecording(); // Avvia la registrazione audio

        // Crea un nuovo ScheduledExecutorService per l'inferenza periodica
        executorService = Executors.newSingleThreadScheduledExecutor();

        // Pianifica l'esecuzione del task di classificazione
        executorService.scheduleAtFixedRate(
                new Runnable() {
                    @Override
                    public void run() {
                        // Imposta la priorità del thread per un'acquisizione audio fluida
                        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

                        // Carica i campioni audio dal record nel TensorAudio
                        tensorAudio.load(record);

                        // Esegui l'inferenza e ottieni i risultati
                        long startTime = System.currentTimeMillis();
                        List<Classifications> classifications = classifier.classify(tensorAudio);
                        long endTime = System.currentTimeMillis();
                        long inferenceTime = endTime - startTime;

                        // Estrai la lista di Category dalla prima Classifications
                        List<Category> output = classifications.stream()
                                .flatMap(c -> c.getCategories().stream())
                                .collect(Collectors.toList());

                        // Invia i risultati al listener
                        classifierListener.onResults(output, inferenceTime);
                    }
                },
                0, // Ritardo iniziale
                CLASSIFIER_INTERVAL_MS, // Intervallo tra le esecuzioni
                TimeUnit.MILLISECONDS
        );
        Log.i(TAG, "Registrazione e classificazione avviate.");
    }

    // Ferma la registrazione e rilascia le risorse
    public void stop() {
        if (!isRecording.get()) {
            Log.d(TAG, "Nessuna registrazione in corso da fermare.");
            return;
        }

        isRecording.set(false); // Imposta lo stato di registrazione su false

        // Ferma l'executor e l'AudioRecord
        if (executorService != null) {
            executorService.shutdownNow(); // Ferma immediatamente tutti i task in attesa
            executorService = null;
        }
        if (record != null) {
            record.stop(); // Ferma la registrazione
            record.release(); // Rilascia le risorse dell'AudioRecord
            record = null;
        }

        isClassifierInitialized.set(false); // Reimposta lo stato di inizializzazione
        Log.i(TAG, "Registrazione e classificazione interrotte, risorse rilasciate.");
    }

    // Metodo per controllare se la registrazione è in corso
    public boolean isRecording() {
        return isRecording.get();
    }

    // Metodo per controllare se il classificatore è inizializzato
    public boolean isClassifierInitialized() {
        return isClassifierInitialized.get();
    }
}
