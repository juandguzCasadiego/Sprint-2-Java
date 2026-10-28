package com.example.parchapp.data;

import com.example.parchapp.domain.model.CampusPlace;
import com.example.parchapp.util.Callback;
import com.example.parchapp.util.MainThread;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/* Reads campusAreas once per process: it is reference data that changes only when reseeded. Firestore's offline cache also serves it without a connection. */
class FirestoreCampusPlaceRepository implements CampusPlaceRepository {
    private final FirebaseFirestore db;
    private List<CampusPlace> cached;

    FirestoreCampusPlaceRepository(FirebaseFirestore db) {
        this.db = db;
    }

    @Override
    public void getPlaces(Callback<List<CampusPlace>> callback) {
        if (cached != null) {
            List<CampusPlace> places = cached;
            MainThread.post(() -> callback.onSuccess(places));
            return;
        }
        db.collection("campusAreas").orderBy("order").get()
                .addOnSuccessListener(snapshot -> {
                    List<CampusPlace> places = new ArrayList<>();
                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        places.add(fromSnapshot(doc));
                    }
                    if (!places.isEmpty()) {
                        cached = Collections.unmodifiableList(places);
                    }
                    callback.onSuccess(places);
                })
                .addOnFailureListener(callback::onError);
    }

    @SuppressWarnings("unchecked")
    private static CampusPlace fromSnapshot(DocumentSnapshot doc) {
        Object aliases = doc.get("aliases");
        List<String> names = new ArrayList<>();
        if (aliases instanceof List) {
            for (Object alias : (List<Object>) aliases) {
                names.add(String.valueOf(alias));
            }
        }
        Number lat = doc.get("lat") instanceof Number ? (Number) doc.get("lat") : null;
        Number lng = doc.get("lng") instanceof Number ? (Number) doc.get("lng") : null;
        return new CampusPlace(doc.getId(), String.valueOf(doc.get("name")),
                lat == null ? null : lat.doubleValue(), lng == null ? null : lng.doubleValue(), names);
    }
}
