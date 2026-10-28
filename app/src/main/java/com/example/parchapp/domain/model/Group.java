package com.example.parchapp.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Group {
    private final String id;
    private final String name;
    private final String icon;
    private final String planTitle;
    private final List<Member> members;
    private final List<String> goingIds;
    private final List<String> maybeIds;
    private final List<String> pendingInvites;
    /* Who created the group; empty for groups that have no owner (the seeded demo groups). */
    private final String ownerId;

    public Group(String id, String name, String icon, String planTitle, List<Member> members,
                 List<String> goingIds, List<String> maybeIds, List<String> pendingInvites) {
        this(id, name, icon, planTitle, members, goingIds, maybeIds, pendingInvites, "");
    }

    public Group(String id, String name, String icon, String planTitle, List<Member> members,
                 List<String> goingIds, List<String> maybeIds, List<String> pendingInvites, String ownerId) {
        this.ownerId = ownerId == null ? "" : ownerId;
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.planTitle = planTitle;
        this.members = new ArrayList<>(members);
        this.goingIds = new ArrayList<>(goingIds);
        this.maybeIds = new ArrayList<>(maybeIds);
        this.pendingInvites = new ArrayList<>(pendingInvites);
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getIcon() {
        return icon;
    }

    public String getPlanTitle() {
        return planTitle;
    }

    public List<Member> getMembers() {
        return Collections.unmodifiableList(members);
    }

    public int getSize() {
        return members.size();
    }

    public List<String> getGoingIds() {
        return Collections.unmodifiableList(goingIds);
    }

    public List<String> getMaybeIds() {
        return Collections.unmodifiableList(maybeIds);
    }

    public List<String> getPendingInvites() {
        return Collections.unmodifiableList(pendingInvites);
    }

    public int getGoingCount() {
        return goingIds.size();
    }

    /* In this scenario we have members plus anyone who answered without being a member yet, such as an invited friend. */
    public int getRsvpTotal() {
        return Math.max(members.size(), goingIds.size());
    }
}
