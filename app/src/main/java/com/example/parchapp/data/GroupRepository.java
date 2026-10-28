package com.example.parchapp.data;

import com.example.parchapp.domain.model.Group;
import com.example.parchapp.util.Callback;

import java.util.List;

/* Single access point for groups. Controllers depend in this on this interface only, never on Firestore. <p>Writes are applied locally first, so they work offline. Observers see the change right away through {@link GroupListener}; syncing with the server happens in the background. */
public interface GroupRepository {
    void getGroup(String groupId, Callback<Group> callback);

    void getGroups(String userId, Callback<List<Group>> callback);

    // f6: saves a new group. the group comes without id and the callback gets it with its new id
    /* category: one of the group categories the backend accepts (study, social, sports, living). The pending invitations (emails) are saved after the backend creates the group. */
    void createGroup(Group group, String category, Callback<Group> callback);

    /* Subscribes to live updates. The listener also receives the current value. */
    void observeGroup(String groupId, GroupListener listener);

    /* Call from {@code onStop()} to avoid leaking the screen. */
    void removeGroupListener(String groupId, GroupListener listener);

    void setRsvp(String groupId, String userId, boolean going);

    void addInvite(String groupId, String email);

    void removeInvite(String groupId, String email);
}
