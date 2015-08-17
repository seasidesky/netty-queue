package org.mitallast.queue.raft.state;

import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import com.google.inject.Inject;
import org.mitallast.queue.Version;
import org.mitallast.queue.common.component.AbstractLifecycleComponent;
import org.mitallast.queue.common.settings.Settings;
import org.mitallast.queue.transport.DiscoveryNode;
import org.mitallast.queue.transport.TransportService;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class ClusterService extends AbstractLifecycleComponent {
    private final Map<DiscoveryNode, MemberState> members = new HashMap<>();
    private final List<MemberState> activeMembers = new ArrayList<>();
    private final List<MemberState> passiveMembers = new ArrayList<>();
    private final TransportService transportService;

    @Inject
    public ClusterService(Settings settings, TransportService transportService) {
        super(settings);
        this.transportService = transportService;
    }

    public ClusterService addMember(MemberState member) {
        if (members.putIfAbsent(member.getNode(), member) == null) {
            if (member.getType() == MemberState.Type.ACTIVE) {
                addActiveMember(member);
            } else {
                addPassiveMember(member);
            }
        }
        return this;
    }

    private void addActiveMember(MemberState member) {
        activeMembers.add(member);
        logger.info("add active member {}", member.getNode());
        logger.info("active members: {}", activeMembers);
    }

    private void addPassiveMember(MemberState member) {
        passiveMembers.add(member);
    }

    ClusterService removeMember(MemberState member) {
        members.remove(member.getNode());
        if (member.getType() == MemberState.Type.ACTIVE) {
            removeActiveMember(member);
        } else {
            removePassiveMember(member);
        }
        return this;
    }

    private void removeActiveMember(MemberState member) {
        Iterator<MemberState> iterator = activeMembers.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getNode().equals(member.getNode())) {
                iterator.remove();
            }
        }
    }

    private void removePassiveMember(MemberState member) {
        Iterator<MemberState> iterator = passiveMembers.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getNode().equals(member.getNode())) {
                iterator.remove();
            }
        }
    }

    public MemberState getMember(DiscoveryNode node) {
        return members.get(node);
    }

    public ImmutableList<MemberState> getActiveMembers() {
        return ImmutableList.copyOf(activeMembers);
    }

    public ImmutableList<MemberState> getPassiveMembers() {
        return ImmutableList.copyOf(passiveMembers);
    }

    public ImmutableList<MemberState> getMembers() {
        return ImmutableList.copyOf(members.values());
    }

    public ImmutableList<DiscoveryNode> activeNodes() {
        return ImmutableList.copyOf(activeMembers.stream().map(MemberState::getNode).collect(Collectors.toList()));
    }

    public ImmutableList<DiscoveryNode> passiveNodes() {
        return ImmutableList.copyOf(passiveMembers.stream().map(MemberState::getNode).collect(Collectors.toList()));
    }

    public ImmutableList<DiscoveryNode> nodes() {
        return ImmutableList.copyOf(members.values().stream().map(MemberState::getNode).collect(Collectors.toList()));
    }

    public boolean containsNode(DiscoveryNode node) {
        return members.containsKey(node);
    }

    @Override
    protected void doStart() throws IOException {
        MemberState.Type type = settings.getAsBoolean("raft.passive", false) ? MemberState.Type.PASSIVE : MemberState.Type.ACTIVE;
        addMember(new MemberState(transportService.localNode(), type));
        String[] nodes = settings.getAsArray("raft.cluster.nodes");
        for (String node : nodes) {
            HostAndPort hostAndPort = HostAndPort.fromString(node);
            if (!hostAndPort.equals(transportService.localAddress())) {
                DiscoveryNode discoveryNode = new DiscoveryNode(node, hostAndPort, Version.CURRENT);
                addMember(new MemberState(discoveryNode, MemberState.Type.ACTIVE));
            }
        }
    }

    @Override
    protected void doStop() throws IOException {

    }

    @Override
    protected void doClose() throws IOException {

    }
}