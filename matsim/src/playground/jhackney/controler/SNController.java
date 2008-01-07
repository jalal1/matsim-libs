/* *********************************************************************** *
 * project: org.matsim.*
 * SNController.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
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

package playground.jhackney.controler;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

import org.matsim.controler.Controler;
import org.matsim.events.algorithms.EventWriterTXT;
import org.matsim.gbl.Gbl;
import org.matsim.plans.Knowledge;
import org.matsim.plans.Person;
import org.matsim.plans.Plan;
import org.matsim.plans.Plans;
import org.matsim.plans.PlansWriter;
import org.matsim.replanning.PlanStrategy;
import org.matsim.replanning.StrategyManager;
import org.matsim.replanning.selectors.BestPlanSelector;
import org.matsim.roadpricing.RoadPricingScoringFunctionFactory;
import org.matsim.scoring.CharyparNagelScoringFunctionFactory;
import org.matsim.scoring.EventsToScore;
import org.matsim.world.algorithms.WorldBottom2TopCompletion;

import playground.jhackney.interactions.NonSpatialInteractor;
import playground.jhackney.interactions.SocializingOpportunity;
import playground.jhackney.interactions.SpatialInteractor;
import playground.jhackney.interactions.SpatialSocialOpportunityTracker;
import playground.jhackney.io.PajekWriter1;
import playground.jhackney.replanning.SNFacilitySwitcher;
import playground.jhackney.scoring.SNScoringFunctionFactory01;
import playground.jhackney.socialnet.SocialNetwork;
import playground.jhackney.statistics.SocialNetworkStatistics;

public class SNController extends Controler {

	private boolean SNFLAG = true;
	public static String SOCNET_OUT_DIR = null;

	SocialNetwork snet;
	SocialNetworkStatistics snetstat;
	PajekWriter1 pjw;
	NonSpatialInteractor plansInteractorNS;//non-spatial (not observed, ICT)
	SpatialInteractor plansInteractorS;//spatial (face to face)
	int max_sn_iter;
	String [] infoToExchange;//type of info for non-spatial exchange is read in 
	public static String activityTypesForEncounters[]={"home","work","shop","education","leisure"};

	SpatialSocialOpportunityTracker gen2 = new SpatialSocialOpportunityTracker();
	Collection<SocializingOpportunity> socialEvents=null;

	boolean hackSocNets = true;

//	Variables for allocating the spatial meetings among different types of activities
	double fractionS[];
	HashMap<String,Double> rndEncounterProbs= new HashMap<String,Double>();
//	New variables for replanning
	int replan_interval;

	@Override
	protected void loadData() {

//		loadWorld();
		this.facilities = loadFacilities();
		this.network = loadNetwork();
		this.population = loadPopulation();
		// Stitch together the world
		//Gbl.getWorld().complete();
		new WorldBottom2TopCompletion().run(Gbl.getWorld());

		System.out.println(" Initializing agent knowledge ...");
		initializeKnowledge(population);
		System.out.println("... done");
		System.out.println("Load social network here");
	}

	@Override
	protected void startup() {
		super.startup();

		System.out.println("First spatial interactions of social network here");
		System.out.println("Initialize the output directory for social networks (?)");
		System.out.println("----------Initialization of social network -------------------------------------");
		snsetup();
	}

	@Override
	/**
	 * This is a test StrategyManager to see if the replanning works within the social network iterations.
	 * @author jhackney
	 * @return
	 */
	protected StrategyManager loadStrategyManager() {
		StrategyManager manager = new StrategyManager();

		String maxvalue = this.config.findParam("strategy", "maxAgentPlanMemorySize");
		manager.setMaxPlansPerAgent(Integer.parseInt(maxvalue));

		// Best-scoring plan chosen each iteration
		PlanStrategy strategy1 = new PlanStrategy(new BestPlanSelector());

		// Social Network Facility Exchange test
		System.out.println("### NOTE THAT FACILITY SWITCHER IS HARD-CODED TO RANDOM SWITCHING OF FACILITIES FROM KNOWLEDGE");
		System.out.println("### NOTE THAT YOU SHOULD EXCHANGE KNOWLEDGE BASED ON ITS VALUE");
		strategy1.addStrategyModule(new SNFacilitySwitcher());


		// Social Network Facility Exchange for all agents
		manager.addStrategy(strategy1, 1.0);
		return manager;
	}
	
	
	@Override
	protected void finishIteration(final int iteration){
		super.finishIteration(iteration);

		System.out.println("finishIteration: Note setting snIter = iteration for now");
		int snIter = iteration;
		
		System.out.println(" Calculating and reporting network statistics ...");
		snetstat.calculate(snIter, snet, population);
		System.out.println(" ... done");

		System.out.println(" Writing out social network for iteration " + snIter + " ...");
		pjw.write(snet.getLinks(), population, snIter);
		System.out.println(" ... done");	
	}

	@Override
	protected void setupIteration(final int iteration) {
		System.out.println("setupIteration: Note setting snIter = iteration for now");
		int snIter = iteration;
		
		this.fireControlerSetupIterationEvent(iteration);
		// TODO [MR] use events.resetHandlers();
		this.travelTimeCalculator.resetTravelTimes();	// reset, so we can collect the new events and build new travel times for the next iteration

		this.eventwriter = new EventWriterTXT(getIterationFilename(Controler.FILENAME_EVENTS));
		this.events.addHandler(this.eventwriter);
		if (this.planScorer == null) {
			if (Gbl.useRoadPricing()) {
				this.planScorer = new EventsToScore(this.population, new RoadPricingScoringFunctionFactory(this.tollCalc, new CharyparNagelScoringFunctionFactory()));
			}else if (SNFLAG){
				this.planScorer = new EventsToScore(this.population, new SNScoringFunctionFactory01());
			} else {
				this.planScorer = new EventsToScore(this.population, new CharyparNagelScoringFunctionFactory());
			}
			this.events.addHandler(this.planScorer);
		} else {
			this.planScorer.reset(iteration);
		}

		// collect and average volumes information in iterations *6-*0, e.g. it.6-10, it.16-20, etc
		if ((iteration % 10 == 0) || (iteration % 10 >= 6)) {
			this.volumes.reset(iteration);
			this.events.addHandler(this.volumes);
		}

		System.out.println("#### Be careful resetting leg times now. You use this in calculating the score later");
		this.legTimes.reset(iteration);

		// dump plans every 10th iteration
		if ((iteration % 10 == 0) || (iteration < 3)) {
			printNote("", "dumping all agents' plans...");
			this.stopwatch.beginOperation("dump all plans");
			String outversion = this.config.plans().getOutputVersion();
			PlansWriter plansWriter = new PlansWriter(this.population, getIterationFilename(Controler.FILENAME_PLANS), outversion);
			plansWriter.setUseCompression(true);
			plansWriter.write();
			this.stopwatch.endOperation("dump all plans");
			printNote("", "done dumping plans.");
		}

		if(total_spatial_fraction(fractionS)>0){

			//if(iteration == 1 or WHATEVER)
			System.out.println("  Generating [Spatial] socializing opportunities ...");
			System.out.println("   Mapping which agents were doing what, where, and when");
			// Create the social opportunities from plans (updated each time plans change)
			// OK to initialize from plans but do this from events if events != null!
			socialEvents = gen2.generate(population);
			System.out.println("...finished.");

			//}// end if iteration == WHATEVER

			// Agents interact at the social opportunities
			System.out.println("  Agents interact at the social opportunities ...");
			plansInteractorS.interact(socialEvents, rndEncounterProbs, snIter);

		}else{
			System.out.println("     (none)");
		}
		System.out.println(" ... Spatial interactions done\n");

		System.out.println(" Removing social links ...");
		snet.removeLinks(snIter);
		System.out.println(" ... done");

		System.out.println(" Non-Spatial interactions ...");
		for (int ii = 0; ii < infoToExchange.length; ii++) {
			String facTypeNS = infoToExchange[ii];

			//	Geographic Knowledge about all types of places is exchanged
			if (!facTypeNS.equals("none")) {
				System.out.println("  Geographic Knowledge about all types of places is being exchanged ...");
				plansInteractorNS.exchangeGeographicKnowledge(facTypeNS, snIter);
			}
		}

		// Exchange of knowledge about people
		double fract_intro=Double.parseDouble(this.config.socnetmodule().getTriangles());
		if (fract_intro > 0) {
			System.out.println("  Knowledge about other people is being exchanged ...");
			plansInteractorNS.exchangeSocialNetKnowledge(snIter);
		}

		System.out.println("  ... done");

		if(iteration == minIteration){
			makeSNIterationPath(snIter);
			makeSNIterationPath(iteration, snIter);
		}

	}


	void initializeKnowledge( Plans plans ){

		// Knowledge is already initialized in some plans files
		// Map agents' knowledge (Activities) to their experience in the plans (Acts)

		for( Person person : plans.getPersons().values() ){

			Knowledge k = person.getKnowledge();
			if(k ==null){
				k = person.createKnowledge("created by " + this.getClass().getName());
			}
			// Initialize knowledge to the facilities that are in all initial plans
			Iterator<Plan> piter=person.getPlans().iterator();
			while (piter.hasNext()){
				Plan plan = piter.next();
				k.map.matchActsToActivities(plan);
			}
		}
	}

	private void snsetup() {

//		Config config = Gbl.getConfig();

		max_sn_iter = Integer.parseInt(config.socnetmodule().getNumIterations());
		replan_interval = Integer.parseInt(config.socnetmodule().getRPInt());
		String rndEncounterProbString = config.socnetmodule().getFacWt();
		String interactorNSFacTypesString = config.socnetmodule().getXchange();
		infoToExchange = getFacTypes(interactorNSFacTypesString);
		fractionS = getActivityTypeAllocation(rndEncounterProbString);
		rndEncounterProbs = getActivityTypeAllocationMap(activityTypesForEncounters, rndEncounterProbString);

		System.out.println(" Instantiating the Pajek writer ...");

		pjw = new PajekWriter1(SOCNET_OUT_DIR, facilities);
		System.out.println("... done");

		System.out.println(" Initializing the social network ...");
		snet = new SocialNetwork(population);
		System.out.println("... done");

		System.out.println(" Calculating the statistics of the initial social network)...");
		snetstat=new SocialNetworkStatistics();
		snetstat.openFiles();
		snetstat.calculate(0, snet, population);
		System.out.println(" ... done");

		System.out.println(" Writing out the initial social network ...");
		pjw.write(snet.getLinks(), population, 0);
		System.out.println("... done");

		System.out.println(" Setting up the NonSpatial interactor ...");
		plansInteractorNS=new NonSpatialInteractor(snet);
		System.out.println("... done");

		System.out.println(" Setting up the Spatial interactor ...");
		plansInteractorS=new SpatialInteractor(snet);
		System.out.println("... done");
	}

	/**
	 * A method for decyphering the config codes. Part of configuration
	 * reader. Replace eventually with a routine that runs all of the
	 * facTypes but uses a probability for each one, summing to 1.0. Change
	 * the interactors accordingly.
	 * 
	 * @param longString
	 * @return
	 */
	private String[] getFacTypes(String longString) {
		// TODO Auto-generated method stub
		String patternStr = ",";
		String[] s;
		Gbl
		.noteMsg(this.getClass(), "getFacTypes",
		"!!add keyword\"any\" and a new interact method to exchange info of any factility types (compatible with probabilities)");
		if (longString.equals("all-p")) {
			s = new String[5];
			s[0] = "home";
			s[1] = "work";
			s[2] = "education";
			s[3] = "leisure";
			s[4] = "shop";
		} else if (longString.equals("all+p")) {
			s = new String[6];
			s[0] = "home";
			s[1] = "work";
			s[3] = "education";
			s[4] = "leisure";
			s[5] = "shop";
			s[6] = "person";
		} else {
			s = longString.split(patternStr);
		}
		for (int i = 0; i < s.length; i++) {
			// if(s[i]!="home"&&s[i]!="work"&&s[i]!="education"&&s[i]!="leisure"&&s[i]!="shop"&&s[i]!="person"&&s[i]!="none"){
			if (!s[i].equals("home") && !s[i].equals("work") && !s[i].equals("education") && !s[i].equals("leisure")
					&& !s[i].equals("shop") && !s[i].equals("person") && !s[i].equals("none")) {
				System.out.println(this.getClass() + ":" + s[i]);
				Gbl.errorMsg("Error on type of info to exchange. Check config file. Use commas with no spaces");
			}
		}
		return s;
	}

	private double[] getActivityTypeAllocation(String longString) {
		String patternStr = ",";
		String[] s;
		s = longString.split(patternStr);
		double[] w = new double[s.length];
		double sum = 0.;
		for (int i = 0; i < s.length; i++) {
			w[i] = Double.valueOf(s[i]).doubleValue();
			if(w[i]<0.||w[i]>1.){
				Gbl.errorMsg("All parameters \"s_weights\" must be >0 and <1. Check config file.");
			}
			sum=sum+w[i];
		}
		if(s.length!=5){
			Gbl.errorMsg("Number of weights for spatial interactions must equal number of facility types. Check config.");
		}
		if(sum<0){
			Gbl.errorMsg("At least one weight for the type of information exchange or meeting place must be > 0, check config file.");
		}
		return w;
	}
	private HashMap<String,Double> getActivityTypeAllocationMap(String[] types, String longString) {
		String patternStr = ",";
		String[] s;
		HashMap<String,Double> map = new HashMap<String,Double>();
		s = longString.split(patternStr);
		double[] w = new double[s.length];
		double sum = 0.;
		for (int i = 0; i < s.length; i++) {
			w[i] = Double.valueOf(s[i]).doubleValue();
			if(w[i]<0.||w[i]>1.){
				Gbl.errorMsg("All parameters \"s_weights\" must be >0 and <1. Check config file.");
			}
			sum=sum+w[i];
			map.put(types[i],w[i]);
		}
		if(s.length!=5){
			Gbl.errorMsg("Number of weights for spatial interactions must equal number of facility types. Check config.");
		}
		if(sum<0){
			Gbl.errorMsg("At least one weight for the type of information exchange or meeting place must be > 0, check config file.");
		}
		return map;
	}    

	private double total_spatial_fraction(double[] fractionS2) {
//		See if we use spatial interaction at all: sum of these must > 0 or else no spatial
//		interactions take place
		double total_spatial_fraction=0;
		for (int jjj = 0; jjj < fractionS2.length; jjj++) {
			total_spatial_fraction = total_spatial_fraction + fractionS2[jjj];
		}
		return total_spatial_fraction;
	}

	private final void makeSNIterationPath(int iteration) {
		new File(getSNIterationPath(iteration)).mkdir();
	}
	private final void makeSNIterationPath(int iteration, int snIter) {
		System.out.println(getSNIterationPath(iteration, snIter));
		File iterationOutFile= new File(getSNIterationPath(iteration, snIter));
		iterationOutFile.mkdir();
//		if (!iterationOutFile.mkdir()) {
//		Gbl.errorMsg("The output directory " + iterationOutFile + " could not be created. Does its parent directory exist?");
//		}
	}

	/**
	 * returns the path to the specified social network iteration directory. The directory path does not include the trailing '/'
	 * @param iteration the iteration the path to should be returned
	 * @return path to the specified iteration directory
	 */
	public final static String getSNIterationPath(int snIter) {
		return outputPath + "/" + DIRECTORY_ITERS + "/"+snIter;
	}
	/**
	 * returns the path to the specified iteration directory,
	 * including social network iteration. The directory path does not include the trailing '/'
	 * @param iteration the iteration the path to should be returned
	 * @return path to the specified iteration directory
	 */
	public final static String getSNIterationPath(int iteration, int sn_iter) {
		return outputPath + "/" + DIRECTORY_ITERS + "/"+sn_iter + "/it." + iteration;
	}
}
