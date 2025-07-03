package com.example.kspotting; 

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

// Importazioni necessarie per TensorFlow Lite Task Library
import org.tensorflow.lite.support.audio.TensorAudio;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.audio.classifier.AudioClassifier;
import org.tensorflow.lite.task.audio.classifier.AudioClassifier.AudioClassifierOptions;
import org.tensorflow.lite.task.core.BaseOptions;
import org.tensorflow.lite.task.audio.classifier.Classifications;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class AudioClassificationHelper {

    private static final String TAG = "AudioClassifierHelper";
    private static final String MODEL_NAME = "speech_commands.tflite";
    private static final long CLASSIFIER_INTERVAL_MS = 200; // Intervallo di inferenza per responsività

    private final Context context;
    private final ClassifierListener classifierListener;
    private AudioClassifier classifier;
    private TensorAudio tensorAudio;
    private AudioRecord record;
    private ScheduledExecutorService executorService;
    private AtomicBoolean isRecording = new AtomicBoolean(false);
    private AtomicBoolean isClassifierInitialized = new AtomicBoolean(false);

    public interface ClassifierListener {
        void onError(String error);
        void onResults(List<Category> results, long inferenceTime);
    }

    public AudioClassificationHelper(Context context, ClassifierListener listener) {
        this.context = context;
        this.classifierListener = listener;
        initClassifier();
    }

    private void initClassifier() {
        releaseResources(); // Assicurati di rilasciare le risorse precedenti

        try {
            BaseOptions.Builder baseOptionsBuilder = BaseOptions.builder()
                    .setNumThreads(2);

            AudioClassifierOptions options =
                    AudioClassifierOptions.builder()
                            .setBaseOptions(baseOptionsBuilder.build())
                            .setMaxResults(5)
                            .build();

            classifier = AudioClassifier.createFromFileAndOptions(context, MODEL_NAME, options);
            tensorAudio = classifier.createInputTensorAudio();

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

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord non inizializzato correttamente.");
                classifierListener.onError("Microfono non disponibile o inizializzazione fallita.");
                releaseResources();
                return;
            }

            isClassifierInitialized.set(true);
            Log.d(TAG, "Classificatore TFLite e AudioRecord inizializzati con successo in Helper.");
        } catch (IOException e) {
            Log.e(TAG, "Errore nel caricamento del modello TFLite: " + e.getMessage());
            classifierListener.onError("Errore nel caricamento del modello: " + e.getMessage());
            releaseResources();
        } catch (RuntimeException e) {
            Log.e(TAG, "Errore in fase di runtime durante l'inizializzazione: " + e.getMessage());
            classifierListener.onError("Errore di runtime nell'inizializzazione: " + e.getMessage() + ". Assicurati che il modello sia valido.");
            releaseResources();
        }
    }

    public void start() {
        if (!isClassifierInitialized.get()) {
            classifierListener.onError("Classificatore non inizializzato. Impossibile avviare la registrazione.");
            return;
        }
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            classifierListener.onError("AudioRecord non pronto. Inizializzare il classificatore prima di avviare.");
            Log.e(TAG, "AudioRecord non pronto allo start, stato: " + (record != null ? record.getState() : "null"));
            return;
        }

        if (isRecording.get()) {
            Log.d(TAG, "La registrazione è già in corso.");
            return;
        }

        isRecording.set(true);
        record.startRecording();

        executorService = Executors.newSingleThreadScheduledExecutor();

        executorService.scheduleAtFixedRate(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!isRecording.get() || classifier == null || tensorAudio == null || record == null) {
                            Log.w(TAG, "Skipping inference: resources not ready in helper.");
                            return;
                        }

                        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

                        try {
                            tensorAudio.load(record);
                        } catch (IllegalStateException e) {
                            Log.e(TAG, "Failed to load audio from AudioRecord in helper: " + e.getMessage());
                            classifierListener.onError("Errore durante l'acquisizione audio: " + e.getMessage());
                            stop();
                            return;
                        }

                        long startTime = System.currentTimeMillis();
                        List<Classifications> classifications = classifier.classify(tensorAudio);
                        long endTime = System.currentTimeMillis();
                        long inferenceTime = endTime - startTime;

                        List<Category> output = classifications.stream()
                                .flatMap(c -> c.getCategories().stream())
                                .collect(Collectors.toList());

                        classifierListener.onResults(output, inferenceTime);
                    }
                },
                0,
                CLASSIFIER_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
        Log.d(TAG, "Registrazione e classificazione avviate in Helper.");
    }

    public void stop() {
        if (!isRecording.get() && !isClassifierInitialized.get()) {
            Log.d(TAG, "Nessuna registrazione o classificatore attivo da fermare/rilasciare in Helper.");
            return;
        }
        releaseResources();
        Log.d(TAG, "Registrazione e classificazione interrotte, risorse rilasciate in Helper.");
    }

    private void releaseResources() {
        isRecording.set(false);

        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        if (record != null) {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
            record.release();
            record = null;
        }
        if (classifier != null) {
            classifier.close();
            classifier = null;
        }
        tensorAudio = null;

        isClassifierInitialized.set(false);
    }

    public boolean isRecording() {
        return isRecording.get();
    }

    public boolean isClassifierInitialized() {
        return isClassifierInitialized.get();
    }
}
