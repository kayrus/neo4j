/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.discovery;

import java.util.List;

import com.hazelcast.config.InterfacesConfig;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MemberAttributeConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.TcpIpConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.MemberAttributeEvent;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.core.MembershipListener;
import com.hazelcast.instance.GroupProperties;
import com.hazelcast.instance.GroupProperty;

import org.neo4j.coreedge.core.CoreEdgeClusterSettings;
import org.neo4j.coreedge.identity.ClusterId;
import org.neo4j.coreedge.identity.MemberId;
import org.neo4j.helpers.AdvertisedSocketAddress;
import org.neo4j.helpers.ListenSocketAddress;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;

class HazelcastCoreTopologyService extends LifecycleAdapter implements CoreTopologyService, MembershipListener
{
    private final Config config;
    private final MemberId myself;
    private final Log log;
    private final CoreTopologyListenerService listenerService;
    private final Log userLog;
    private String membershipRegistrationId;

    private HazelcastInstance hazelcastInstance;

    HazelcastCoreTopologyService( Config config, MemberId myself, LogProvider logProvider, LogProvider userLogProvider )
    {
        this.config = config;
        this.myself = myself;
        this.listenerService = new CoreTopologyListenerService();
        this.log = logProvider.getLog( getClass() );
        this.userLog = userLogProvider.getLog( getClass() );
    }

    @Override
    public void addCoreTopologyListener( Listener listener )
    {
        listenerService.addCoreTopologyListener( listener );
        listener.onCoreTopologyChange( coreServers() );
    }

    @Override
    public boolean casClusterId( ClusterId clusterId )
    {
        return HazelcastClusterTopology.casClusterId( hazelcastInstance, clusterId );
    }

    @Override
    public void memberAdded( MembershipEvent membershipEvent )
    {
        log.info( "Core member added %s", membershipEvent );
        log.info( "Current core topology is %s", coreServers() );
        listenerService.notifyListeners( coreServers());
    }

    @Override
    public void memberRemoved( MembershipEvent membershipEvent )
    {
        log.info( "Core member removed %s", membershipEvent );
        log.info( "Current core topology is %s", coreServers() );
        listenerService.notifyListeners( coreServers());
    }

    @Override
    public void memberAttributeChanged( MemberAttributeEvent memberAttributeEvent )
    {
    }

    @Override
    public void start()
    {
        hazelcastInstance = createHazelcastInstance();
        log.info( "Cluster discovery service started" );
        membershipRegistrationId = hazelcastInstance.getCluster().addMembershipListener( this );
        listenerService.notifyListeners( coreServers());
    }

    @Override
    public void stop()
    {
        log.info( String.format( "HazelcastCoreTopologyService stopping and unbinding from %s",
                config.get( CoreEdgeClusterSettings.discovery_listen_address ) ) );
        try
        {
            hazelcastInstance.getCluster().removeMembershipListener( membershipRegistrationId );
            hazelcastInstance.getLifecycleService().terminate();
        }
        catch ( Throwable e )
        {
            log.warn( "Failed to stop Hazelcast", e );
        }
    }

    private HazelcastInstance createHazelcastInstance()
    {
        System.setProperty( GroupProperties.PROP_WAIT_SECONDS_BEFORE_JOIN, "1" );

        JoinConfig joinConfig = new JoinConfig();
        joinConfig.getMulticastConfig().setEnabled( false );
        TcpIpConfig tcpIpConfig = joinConfig.getTcpIpConfig();
        tcpIpConfig.setEnabled( true );

        List<AdvertisedSocketAddress> initialMembers =
                config.get( CoreEdgeClusterSettings.initial_discovery_members );
        for ( AdvertisedSocketAddress address : initialMembers )
        {
            tcpIpConfig.addMember( address.toString() );
        }
        log.info( "Discovering cluster with initial members: " + initialMembers );

        NetworkConfig networkConfig = new NetworkConfig();
        ListenSocketAddress hazelcastAddress = config.get( CoreEdgeClusterSettings.discovery_listen_address );
        InterfacesConfig interfaces = new InterfacesConfig();
        interfaces.addInterface( hazelcastAddress.getHostname() );
        networkConfig.setInterfaces( interfaces );
        networkConfig.setPort( hazelcastAddress.getPort() );
        networkConfig.setJoin( joinConfig );

        com.hazelcast.config.Config c = new com.hazelcast.config.Config();
        c.setProperty( GroupProperty.OPERATION_CALL_TIMEOUT_MILLIS, "10000" );
        c.setProperty( GroupProperties.PROP_INITIAL_MIN_CLUSTER_SIZE,
                String.valueOf( minimumClusterSizeThatCanTolerateOneFaultForExpectedClusterSize() ) );
        c.setProperty( GroupProperties.PROP_LOGGING_TYPE, "none" );

        c.setNetworkConfig( networkConfig );

        MemberAttributeConfig memberAttributeConfig = HazelcastClusterTopology.buildMemberAttributes( myself, config );

        c.setMemberAttributeConfig( memberAttributeConfig );
        userLog.info( "Waiting for other members to join cluster before continuing..." );
        return Hazelcast.newHazelcastInstance( c );
    }

    private Integer minimumClusterSizeThatCanTolerateOneFaultForExpectedClusterSize()
    {
        return config.get( CoreEdgeClusterSettings.expected_core_cluster_size ) / 2 + 1;
    }

    @Override
    public EdgeTopology edgeServers()
    {
        return HazelcastClusterTopology.getEdgeTopology( hazelcastInstance, log );
    }

    @Override
    public CoreTopology coreServers()
    {
        return HazelcastClusterTopology.getCoreTopology( hazelcastInstance, log );
    }
}
