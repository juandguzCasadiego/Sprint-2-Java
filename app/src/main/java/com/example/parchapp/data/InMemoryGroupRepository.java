package com.example.parchapp.data;

import com.example.parchapp.domain.model.Group;
import com.example.parchapp.util.Callback;
import com.example.parchapp.util.MainThread;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/* Used when Firebase is not configured. Data lives only while the process is alive. */
class InMemoryGroupRepository extends BaseGroupRepository {

    private final Map<String, Group> groups = DemoData.groups();

    @Override
    public void getGroup(String groupId, Callback<Group> callback) {
        Group group = groups.get(groupId);
        MainThread.post(() -> {
            if (group == null) {
                callback.onError(new NoSuchElementException("Group " + groupId + " not found"));
            } else {
                callback.onSuccess(group);
            }
        });
    }

    @Override
    public void getGroups(String userId, Callback<List<Group>> callback) {
        List<Group> result = new ArrayList<>();
        for (Group group : groups.values()) {
            if (group.getMembers().stream().anyMatch(member -> member.getId().equals(userId))) {
                result.add(group);
            }
        }
        MainThread.post(() -> callback.onSuccess(result));
    }

    @Override
    public void createGroup(Group group, Callback<Group> callback) {
        // without firebase we make the id with the current time
        Group saved = new Group("group_" + System.currentTimeMillis(), group.getName(), group.getIcon(),
                group.getPlanTitle(), group.getMembers(), group.getGoingIds(), group.getMaybeIds(),
                group.getPendingInvites());
        groups.put(saved.getId(), saved);
        MainThread.post(() -> callback.onSuccess(saved));
    }

    @Override
    protected void startObserving(String groupId) {
        Group group = groups.get(groupId);
        if (group != null) {
            MainThread.post(() -> notifyListeners(groups.get(groupId)));
        }
    }

    @Override
    protected void stopObserving(String groupId) {
    }

    @Override
    public void setRsvp(String groupId, String userId, boolean going) {
        Group group = groups.get(groupId);
        if (group == null) {
            return;
        }
        List<String> goingIds = new ArrayList<>(group.getGoingIds());
        List<String> maybeIds = new ArrayList<>(group.getMaybeIds());
        goingIds.remove(userId);
        maybeIds.remove(userId);
        (going ? goingIds : maybeIds).add(userId);
        update(new Group(group.getId(), group.getName(), group.getIcon(), group.getPlanTitle(),
                group.getMembers(), goingIds, maybeIds, group.getPendingInvites()));
    }

    @Override
    public void addInvite(String groupId, String email) {
        Group group = groups.get(groupId);
        if (group == null || group.getPendingInvites().contains(email)) {
            return;
        }
        List<String> invites = new ArrayList<>(group.getPendingInvites());
        invites.add(email);
        update(withInvites(group, invites));
    }

    @Override
    public void removeInvite(String groupId, String email) {
        Group group = groups.get(groupId);
        if (group == null) {
            return;
        }
        List<String> invites = new ArrayList<>(group.getPendingInvites());
        invites.remove(email);
        update(withInvites(group, invites));
    }

    private static Group withInvites(Group group, List<String> invites) {
        return new Group(group.getId(), group.getName(), group.getIcon(), group.getPlanTitle(),
                group.getMembers(), group.getGoingIds(), group.getMaybeIds(), invites);
    }

    private void update(Group group) {
        groups.put(group.getId(), group);
        MainThread.post(() -> notifyListeners(group));
    }
}
