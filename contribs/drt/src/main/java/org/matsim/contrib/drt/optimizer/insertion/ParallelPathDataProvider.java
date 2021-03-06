/* *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2018 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.contrib.drt.optimizer.insertion;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.inject.Named;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.optimizer.VehicleData.Entry;
import org.matsim.contrib.drt.optimizer.insertion.DetourLinksProvider.DetourLinksSet;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.path.OneToManyPathSearch;
import org.matsim.contrib.dvrp.path.OneToManyPathSearch.PathData;
import org.matsim.contrib.dvrp.trafficmonitoring.DvrpTravelTimeModule;
import org.matsim.core.mobsim.framework.events.MobsimBeforeCleanupEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeCleanupListener;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

/**
 * @author michalm
 */
public class ParallelPathDataProvider implements PrecalculablePathDataProvider, MobsimBeforeCleanupListener {
	public static final int MAX_THREADS = 4;

	private final OneToManyPathSearch toPickupPathSearch;
	private final OneToManyPathSearch fromPickupPathSearch;
	private final OneToManyPathSearch toDropoffPathSearch;
	private final OneToManyPathSearch fromDropoffPathSearch;

	private final double stopDuration;

	private final ExecutorService executorService;

	// ==== recalculated by precalculatePathData()
	private Map<Id<Link>, PathData> pathsToPickupMap;
	private Map<Id<Link>, PathData> pathsFromPickupMap;
	private Map<Id<Link>, PathData> pathsToDropoffMap;
	private Map<Id<Link>, PathData> pathsFromDropoffMap;

	public ParallelPathDataProvider(Network network, @Named(DvrpTravelTimeModule.DVRP_ESTIMATED) TravelTime travelTime,
			TravelDisutility travelDisutility, DrtConfigGroup drtCfg) {
		toPickupPathSearch = OneToManyPathSearch.createBackwardSearch(network, travelTime, travelDisutility);
		fromPickupPathSearch = OneToManyPathSearch.createForwardSearch(network, travelTime, travelDisutility);
		toDropoffPathSearch = OneToManyPathSearch.createBackwardSearch(network, travelTime, travelDisutility);
		fromDropoffPathSearch = OneToManyPathSearch.createForwardSearch(network, travelTime, travelDisutility);
		stopDuration = drtCfg.getStopDuration();
		executorService = Executors.newFixedThreadPool(Math.min(drtCfg.getNumberOfThreads(), MAX_THREADS));
	}

	@Override
	public void precalculatePathData(DrtRequest drtRequest, DetourLinksSet detourLinksSet) {
		Link pickup = drtRequest.getFromLink();
		Link dropoff = drtRequest.getToLink();

		double earliestPickupTime = drtRequest.getEarliestStartTime(); // optimistic
		double minTravelTime = 15 * 60; // FIXME inaccurate temp solution: fixed 15 min
		double earliestDropoffTime = earliestPickupTime + minTravelTime + stopDuration;

		// with vehicle insertion filtering -- pathsToPickup is the most computationally demanding task, while
		// pathsFromDropoff is the least demanding one

		// highest computation time (approx. 45% total CPU time)
		Future<Map<Id<Link>, PathData>> pathsToPickupFuture = executorService.submit(() -> {
			// calc backward dijkstra from pickup to ends of selected stops + starts
			return toPickupPathSearch.calcPathDataMap(pickup, detourLinksSet.pickupDetourStartLinks.values(),
					earliestPickupTime);
		});

		// medium computation time (approx. 25% total CPU time)
		Future<Map<Id<Link>, PathData>> pathsFromPickupFuture = executorService.submit(() -> {
			// calc forward dijkstra from pickup to beginnings of selected stops + dropoff
			return fromPickupPathSearch.calcPathDataMap(pickup, detourLinksSet.pickupDetourEndLinks.values(),
					earliestPickupTime);
		});

		// medium computation time (approx. 25% total CPU time)
		Future<Map<Id<Link>, PathData>> pathsToDropoffFuture = executorService.submit(() -> {
			// calc backward dijkstra from dropoff to ends of selected stops
			return toDropoffPathSearch.calcPathDataMap(dropoff, detourLinksSet.dropoffDetourStartLinks.values(),
					earliestDropoffTime);
		});

		// lowest computation time (approx. 5% total CPU time)
		Future<Map<Id<Link>, PathData>> pathsFromDropoffFuture = executorService.submit(() -> {
			// calc forward dijkstra from dropoff to beginnings of selected stops
			return fromDropoffPathSearch.calcPathDataMap(dropoff, detourLinksSet.dropoffDetourEndLinks.values(),
					earliestDropoffTime);
		});

		try {
			// start from earliest (fastest) to latest (slowest)
			pathsFromDropoffMap = pathsFromDropoffFuture.get();
			pathsToDropoffMap = pathsToDropoffFuture.get();
			pathsFromPickupMap = pathsFromPickupFuture.get();
			pathsToPickupMap = pathsToPickupFuture.get();
		} catch (InterruptedException | ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public PathDataSet getPathDataSet(DrtRequest drtRequest, Entry vEntry) {
		return PrecalculablePathDataProvider.getPathDataSet(drtRequest, vEntry, pathsToPickupMap, pathsFromPickupMap,
				pathsToDropoffMap, pathsFromDropoffMap);
	}

	@Override
	public void notifyMobsimBeforeCleanup(@SuppressWarnings("rawtypes") MobsimBeforeCleanupEvent e) {
		executorService.shutdown();
	}
}
