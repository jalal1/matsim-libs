/*
 *  *********************************************************************** *
 *  * project: org.matsim.*
 *  * QSignalsNetworkFactory.java
 *  *                                                                         *
 *  * *********************************************************************** *
 *  *                                                                         *
 *  * copyright       : (C) 2014 by the members listed in the COPYING, *
 *  *                   LICENSE and WARRANTY file.                            *
 *  * email           : info at matsim dot org                                *
 *  *                                                                         *
 *  * *********************************************************************** *
 *  *                                                                         *
 *  *   This program is free software; you can redistribute it and/or modify  *
 *  *   it under the terms of the GNU General Public License as published by  *
 *  *   the Free Software Foundation; either version 2 of the License, or     *
 *  *   (at your option) any later version.                                   *
 *  *   See also COPYING, LICENSE and WARRANTY file                           *
 *  *                                                                         *
 *  * ***********************************************************************
 */
package org.matsim.core.mobsim.qsim.qnetsimengine;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.mobsim.qsim.interfaces.AgentCounter;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimEngine.NetsimInternalInterface;
import org.matsim.vis.snapshotwriters.SnapshotLinkWidthCalculator;

import com.google.inject.Inject;

/**
 * this class adds the turn acceptance logic for signals
 * 
 * @author tthunig
 */
public class QSignalsNetworkFactory extends QNetworkFactory{

	private final QNetworkFactory delegate;
	
	private Scenario scenario;
	private EventsManager events;
	private NetsimEngineContext context;
	private NetsimInternalInterface netsimEngine ;
	
	@Inject
	public QSignalsNetworkFactory(Scenario scenario, EventsManager events) {
		this.scenario = scenario;
		this.events = events;
		if (scenario.getConfig().qsim().isUseLanes()) {
			delegate = new QLanesNetworkFactory(events, scenario);
		} else {
			delegate = new DefaultQNetworkFactory(events, scenario);
		}
	}
	
	@Override
	void initializeFactory(AgentCounter agentCounter, MobsimTimer mobsimTimer, NetsimInternalInterface simEngine1) {
		SnapshotLinkWidthCalculator linkWidthCalculator = new SnapshotLinkWidthCalculator();
		linkWidthCalculator.setLinkWidthForVis( scenario.getConfig().qsim().getLinkWidthForVis() );
		linkWidthCalculator.setLaneWidth( scenario.getNetwork().getEffectiveLaneWidth() );
		
		AbstractAgentSnapshotInfoBuilder agentSnapshotInfoBuilder = QNetsimEngine.createAgentSnapshotInfoBuilder( scenario, linkWidthCalculator );
		
		this.netsimEngine = simEngine1;
		this.context = new NetsimEngineContext( events, scenario.getNetwork().getEffectiveCellSize(), agentCounter, agentSnapshotInfoBuilder, 
				scenario.getConfig().qsim(), mobsimTimer, linkWidthCalculator );
		
		delegate.initializeFactory(agentCounter, mobsimTimer, simEngine1);
	}

	@Override
	QNodeI createNetsimNode(Node node) {
		QNodeImpl.Builder builder = new QNodeImpl.Builder( netsimEngine, context ) ;
		builder.setTurnAcceptanceLogic( new SignalTurnAcceptanceLogic() ) ;
		return builder.build( node ) ;
	}

	@Override
	QLinkI createNetsimLink(Link link, QNodeI queueNode) {
		return delegate.createNetsimLink(link, queueNode);
	}

}
