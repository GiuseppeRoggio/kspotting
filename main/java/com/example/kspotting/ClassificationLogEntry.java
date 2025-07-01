package com.example.kspotting;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Classe per incapsulare un singolo log di classificazione
 * che può essere passato tra Service e Activity tramite Intent.
 * Implementa Parcelable per una serializzazione efficiente.
 */
public class ClassificationLogEntry implements Parcelable {
    public String label;
    public float confidence;
    public long timestamp; // Timestamp dell'inferenza

    public ClassificationLogEntry(String label, float confidence, long timestamp) {
        this.label = label;
        this.confidence = confidence;
        this.timestamp = timestamp;
    }

    // Costruttore per la deserializzazione da Parcel
    protected ClassificationLogEntry(Parcel in) {
        label = in.readString();
        confidence = in.readFloat();
        timestamp = in.readLong();
    }

    // Creator per generare istanze della classe da un Parcel
    public static final Creator<ClassificationLogEntry> CREATOR = new Creator<ClassificationLogEntry>() {
        @Override
        public ClassificationLogEntry createFromParcel(Parcel in) {
            return new ClassificationLogEntry(in);
        }

        @Override
        public ClassificationLogEntry[] newArray(int size) {
            return new ClassificationLogEntry[size];
        }
    };

    @Override
    public int describeContents() {
        return 0; // Nessun descrittore speciale di tipo di oggetto
    }

    // Scrive i dati dell'oggetto nel Parcel
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(label);
        dest.writeFloat(confidence);
        dest.writeLong(timestamp);
    }
}
