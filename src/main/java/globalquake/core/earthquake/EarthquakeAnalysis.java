package globalquake.core.earthquake;

import globalquake.core.GlobalQuake;
import globalquake.core.analysis.BetterAnalysis;
import globalquake.geo.GeoUtils;
import globalquake.geo.taup.TauPTravelTimeCalculator;
import globalquake.intensity.IntensityTable;
import globalquake.sounds.Sounds;
import globalquake.ui.globe.Point2D;
import globalquake.ui.settings.Settings;
import globalquake.utils.monitorable.MonitorableCopyOnWriteArrayList;
import org.tinylog.Logger;

import java.util.*;

public class EarthquakeAnalysis {

    public static final double MIN_RATIO = 16.0;

    public static final int TARGET_EVENTS = 30;

    public static final int QUADRANTS = 16;

    public static final boolean USE_MEDIAN_FOR_ORIGIN = true;
    private static final boolean REMOVE_WEAKEST = false;

    private final List<Earthquake> earthquakes;

    private ClusterAnalysis clusterAnalysis;

    public boolean testing = false;

    public EarthquakeAnalysis() {
        earthquakes = new MonitorableCopyOnWriteArrayList<>();
    }

    public EarthquakeAnalysis(ClusterAnalysis clusterAnalysis, List<Earthquake> earthquakes){
        this.clusterAnalysis = clusterAnalysis;
        this.earthquakes = earthquakes;
    }

    public List<Earthquake> getEarthquakes() {
        return earthquakes;
    }

    public void run() {
        if(clusterAnalysis == null){
            if(GlobalQuake.instance == null){
                return;
            } else {
                clusterAnalysis = GlobalQuake.instance.getClusterAnalysis();
            }
        }
        clusterAnalysis.getClustersReadLock().lock();
        try {
            clusterAnalysis.getClusters().parallelStream().forEach(cluster -> processCluster(cluster, createListOfPickedEvents(cluster)));
        } finally {
            clusterAnalysis.getClustersReadLock().unlock();
        }
        getEarthquakes().parallelStream().forEach(this::calculateMagnitude);
    }

    public void processCluster(Cluster cluster, List<PickedEvent> pickedEvents) {
        if (pickedEvents.isEmpty()) {
            return;
        }

        // Calculation starts only if number of events increases by some %
        if (cluster.getEarthquake() != null) {
            int count = pickedEvents.size();
            if (count >= 24) {
                if (count < cluster.getEarthquake().nextReportEventCount) {
                    return;
                }
                cluster.getEarthquake().nextReportEventCount = (int) (count * 1.2);
                Logger.debug("Next report will be at " + cluster.getEarthquake().nextReportEventCount + " assigns");
            }
        }

        if (cluster.lastEpicenterUpdate == cluster.updateCount) {
            return;
        }

        cluster.lastEpicenterUpdate = cluster.updateCount;


        pickedEvents.sort(Comparator.comparing(PickedEvent::maxRatio));

        // if there is no event stronger than MIN_RATIO, abort
        if (pickedEvents.get(pickedEvents.size() - 1).maxRatio() < MIN_RATIO) {
            return;
        }

        if(REMOVE_WEAKEST) {
            double ratioPercentileThreshold = pickedEvents.get((int) ((pickedEvents.size() - 1) * 0.35)).maxRatio();

            // remove events that are weaker than the threshold and keep at least 8 events
            while (pickedEvents.get(0).maxRatio() < ratioPercentileThreshold && pickedEvents.size() > 8) {
                pickedEvents.remove(0);
            }
        }

        HypocenterFinderSettings finderSettings = createSettings();

        // if in the end there is less than N events, abort
        if (pickedEvents.size() < finderSettings.minStations()) {
            return;
        }

        ArrayList<PickedEvent> selectedEvents = new ArrayList<>();
        selectedEvents.add(pickedEvents.get(0));

        // Selects picked events in a way that they are spaced away as much as possible
        findGoodEvents(pickedEvents, selectedEvents);

        synchronized (cluster.selectedEventsLock) {
            Logger.debug("SELECTED " + selectedEvents.size());
            cluster.setSelected(selectedEvents);
        }


        // There has to be at least some difference in the picked pWave times
        if (!checkDeltaP(selectedEvents, finderSettings)) {
            Logger.debug("Not Enough Delta-P");
            return;
        }

        findHypocenter(selectedEvents, cluster, finderSettings);
    }

    public static HypocenterFinderSettings createSettings() {
        return new HypocenterFinderSettings(Settings.pWaveInaccuracyThreshold, Settings.hypocenterCorrectThreshold,
                Settings.hypocenterDetectionResolution, Settings.minimumStationsForEEW);
    }

    private List<PickedEvent> createListOfPickedEvents(Cluster cluster) {
        List<PickedEvent> result = new ArrayList<>();
        for (Event event : cluster.getAssignedEvents().values()) {
            if(event.isValid()){
                result.add(new PickedEvent(event.getpWave(), event.getLatFromStation(), event.getLonFromStation(), event.getElevationFromStation(), event.maxRatio));
            }
        }

        return result;
    }

    private void findGoodEvents(List<PickedEvent> events, List<PickedEvent> selectedEvents) {
        while (selectedEvents.size() < TARGET_EVENTS) {
            double maxDist = 0;
            PickedEvent furthest = null;
            for (PickedEvent event : events) {
                if (!selectedEvents.contains(event)) {
                    double closest = Double.MAX_VALUE;
                    for (PickedEvent event2 : selectedEvents) {
                        double dist = GeoUtils.greatCircleDistance(event.lat(), event.lon(),
                                event2.lat(), event2.lon());
                        if (dist < closest) {
                            closest = dist;
                        }
                    }
                    if (closest > maxDist) {
                        maxDist = closest;
                        furthest = event;
                    }
                }
            }

            if (furthest == null) {
                break;
            }

            selectedEvents.add(furthest);

            if (selectedEvents.size() == events.size()) {
                break;
            }
        }
    }

    private boolean checkDeltaP(ArrayList<PickedEvent> events, HypocenterFinderSettings finderSettings) {
        events.sort(Comparator.comparing(PickedEvent::pWave));

        long deltaP = events.get((int) ((events.size() - 1) * 0.9)).pWave()
                - events.get((int) ((events.size() - 1) * 0.1)).pWave();

        return deltaP >= Math.max(2000, finderSettings.pWaveInaccuracyThreshold() * 1.75);
    }

    public void findHypocenter(List<PickedEvent> selectedEvents, Cluster cluster, HypocenterFinderSettings finderSettings) {
        if (selectedEvents.isEmpty()) {
            return;
        }

        Logger.debug("==== Searching hypocenter of cluster #" + cluster.getId() + " ====");

        double maxDepth = TauPTravelTimeCalculator.MAX_DEPTH;

        int iterationsDifference = (int) Math.round((finderSettings.resolution() - 40.0) / 14.0);
        double universalMultiplier = getUniversalResolutionMultiplier(finderSettings);

        Logger.debug("Universal multiplier is " + universalMultiplier);
        Logger.debug("Iterations difference: " + iterationsDifference);

        long timeMillis = System.currentTimeMillis();
        long startTime = timeMillis;

        // phase 1 search far
        double _lat = cluster.getAnchorLat();
        double _lon = cluster.getAnchorLon();
        PreliminaryHypocenter bestHypocenter = scanArea(selectedEvents, 100.0 / universalMultiplier, 10000, _lat, _lon, 5 + iterationsDifference, maxDepth, 100.0 / universalMultiplier, finderSettings);
        Logger.debug("FAR: " + (System.currentTimeMillis() - timeMillis));
        Logger.debug(bestHypocenter.correctStations + " / " + bestHypocenter.err);

        // phase 2 search nearby that far
        timeMillis = System.currentTimeMillis();
        _lat = bestHypocenter.lat;
        _lon = bestHypocenter.lon;
        PreliminaryHypocenter hyp = scanArea(selectedEvents, 10.0 / universalMultiplier, 1000, _lat, _lon, 6 + iterationsDifference, maxDepth, 16 / universalMultiplier, finderSettings);
        bestHypocenter = selectBetterHypocenter(hyp, bestHypocenter);
        Logger.debug("CLOSE: " + (System.currentTimeMillis() - timeMillis));
        Logger.debug(bestHypocenter.correctStations + " / " + bestHypocenter.err);

        // phase 3 search nearby of root
        timeMillis = System.currentTimeMillis();
        _lat = cluster.getRootLat();
        _lon = cluster.getRootLon();
        hyp = scanArea(selectedEvents, 10.0 / universalMultiplier, 1000, _lat, _lon, 6 + iterationsDifference, maxDepth, 16 / universalMultiplier, finderSettings);
        bestHypocenter = selectBetterHypocenter(hyp, bestHypocenter);
        Logger.debug("CLOSE TO ROOT: " + (System.currentTimeMillis() - timeMillis));
        Logger.debug(bestHypocenter.correctStations + " / " + bestHypocenter.err);


        // phase 4 find exact area
        timeMillis = System.currentTimeMillis();
        _lat = bestHypocenter.lat;
        _lon = bestHypocenter.lon;
        hyp = scanArea(selectedEvents, 2.0 / universalMultiplier, 100.0, _lat, _lon, 7 + iterationsDifference, maxDepth, 2, finderSettings);
        bestHypocenter = selectBetterHypocenter(hyp, bestHypocenter);
        Logger.debug("EXACT: " + (System.currentTimeMillis() - timeMillis));
        Logger.debug(bestHypocenter.correctStations + " / " + bestHypocenter.err);

        // phase 5 find exact depth
        timeMillis = System.currentTimeMillis();
        _lat = bestHypocenter.lat;
        _lon = bestHypocenter.lon;
        hyp = scanArea(selectedEvents, 1.0 / universalMultiplier, 10.0, _lat, _lon, 10 + iterationsDifference, maxDepth, 0.4 / universalMultiplier, finderSettings);
        bestHypocenter = selectBetterHypocenter(hyp, bestHypocenter);
        Logger.debug("DEPTH: " + (System.currentTimeMillis() - timeMillis));
        Logger.debug(bestHypocenter.correctStations + " / " + bestHypocenter.err);


        postProcess(selectedEvents, cluster, bestHypocenter, finderSettings, startTime);
    }

    private void postProcess(List<PickedEvent> selectedEvents, Cluster cluster, PreliminaryHypocenter bestHypocenterPrelim, HypocenterFinderSettings finderSettings, long startTime) {
        Hypocenter bestHypocenter = bestHypocenterPrelim.finish();
        Hypocenter previousHypocenter = cluster.getPreviousHypocenter();

        double pct = 100 * bestHypocenter.getCorrectness();
        bestHypocenter.setWrongEventsCount(selectedEvents.size() - bestHypocenter.correctStations);

        HypocenterCondition result;
        if ((result = checkConditions(selectedEvents, bestHypocenter, previousHypocenter, cluster)) == HypocenterCondition.OK) {
            updateHypocenter(cluster, bestHypocenter);
        } else {
            Logger.debug(result);

            boolean valid = pct > finderSettings.correctnessThreshold();
            if (!valid && cluster.getEarthquake() != null) {
                getEarthquakes().remove(cluster.getEarthquake());
                cluster.setEarthquake(null);
            }
        }

        cluster.setPreviousHypocenter(bestHypocenter);

        Logger.info("Hypocenter finding finished in: %d ms".formatted( System.currentTimeMillis() - startTime));
    }

    private PreliminaryHypocenter scanArea(List<PickedEvent> events, double distanceResolution, double maxDist,
                                           double _lat, double _lon, int depthIterations, double maxDepth, double distHorizontal, HypocenterFinderSettings finderSettings) {

        List<Double> distances = new ArrayList<>();
        for (double dist = 0; dist < maxDist; dist += distanceResolution) {
            distances.add(dist);
        }

        return (Settings.parallelHypocenterLocations ? distances.parallelStream() : distances.stream()).map(
                distance -> {
                    List<ExactPickedEvent> pickedEvents = createListOfExactPickedEvents(events);
                    HypocenterFinderThreadData threadData = new HypocenterFinderThreadData(pickedEvents.size());
                    getBestAtDist(distance, distHorizontal, _lat, _lon, pickedEvents, depthIterations, maxDepth, finderSettings, threadData);
                    return threadData.bestHypocenter;
                }
        ).reduce(EarthquakeAnalysis::selectBetterHypocenter).orElse(null);
    }

    private List<ExactPickedEvent> createListOfExactPickedEvents(List<PickedEvent> events) {
        List<ExactPickedEvent> result = new ArrayList<>(events.size());
        for (PickedEvent event : events) {
            result.add(new ExactPickedEvent(event));
        }
        return result;
    }

    private static PreliminaryHypocenter selectBetterHypocenter(PreliminaryHypocenter hypocenter1, PreliminaryHypocenter hypocenter2) {
        if (hypocenter1 == null) {
            return hypocenter2;
        } else if (hypocenter2 == null) {
            return hypocenter1;
        }

        if (hypocenter1.correctStations > (int) (hypocenter2.correctStations * 1.3)) {
            return hypocenter1;
        } else if (hypocenter2.correctStations > (int) (hypocenter1.correctStations * 1.3)) {
            return hypocenter2;
        } else {
            return (hypocenter1.correctStations / (Math.pow(hypocenter1.err, 2) + 2.0)) >
                    (hypocenter2.correctStations / (Math.pow(hypocenter2.err, 2) + 2.0))
                    ? hypocenter1 : hypocenter2;
        }
    }

    private void getBestAtDist(double distFromAnchor, double distHorizontal, double _lat, double _lon,
                               List<ExactPickedEvent> events, int depthIterations, double depthEnd,
                               HypocenterFinderSettings finderSettings, HypocenterFinderThreadData threadData) {
        double depthStart = 0;

        double angularResolution = (distHorizontal * 360) / (5 * distFromAnchor + 10);
        angularResolution /= getUniversalResolutionMultiplier(finderSettings);

        GeoUtils.MoveOnGlobePrecomputed precomputed = new GeoUtils.MoveOnGlobePrecomputed();
        Point2D point2D = new Point2D();
        GeoUtils.precomputeMoveOnGlobe(precomputed, _lat, _lon, distFromAnchor);


        for (double ang = 0; ang < 360; ang += angularResolution) {
            GeoUtils.moveOnGlobe(precomputed, point2D, ang);
            double lat = point2D.x;
            double lon = point2D.y;

            calculateDistances(events, lat, lon);
            getBestAtDepth(depthIterations, depthEnd, finderSettings, depthStart, lat, lon, events, threadData);
        }
    }

    private void getBestAtDepth(int depthIterations, double depthEnd, HypocenterFinderSettings finderSettings,
                                double depthStart, double lat, double lon, List<ExactPickedEvent> pickedEvents,
                                HypocenterFinderThreadData threadData) {
        double lowerBound = depthStart; // 0
        double upperBound = depthEnd; // 600

        for (int iteration = 0; iteration < depthIterations; iteration++) {
            double depthA = lowerBound + (upperBound - lowerBound) * (1 / 3.0);
            double depthB = lowerBound + (upperBound - lowerBound) * (2 / 3.0);

            createHypocenter(threadData.hypocenterA, lat, lon, depthA, pickedEvents, finderSettings, threadData);
            createHypocenter(threadData.hypocenterB, lat, lon, depthB, pickedEvents, finderSettings, threadData);

            PreliminaryHypocenter better = selectBetterHypocenter(threadData.hypocenterA, threadData.hypocenterB);
            threadData.setBest(selectBetterHypocenter(threadData.bestHypocenter, better));

            boolean goUp = better == threadData.hypocenterA;
            if (goUp) {
                upperBound = (upperBound + lowerBound) / 2.0;
            } else {
                lowerBound = (upperBound + lowerBound) / 2.0;
            }
        }

        // additionally check 0km and 10 km
        createHypocenter(threadData.hypocenterA, lat, lon, 0, pickedEvents, finderSettings, threadData);
        threadData.setBest(selectBetterHypocenter(threadData.bestHypocenter, threadData.hypocenterA));
        createHypocenter(threadData.hypocenterA, lat, lon, 10, pickedEvents, finderSettings, threadData);
        threadData.setBest(selectBetterHypocenter(threadData.bestHypocenter, threadData.hypocenterA));
    }

    private void createHypocenter(PreliminaryHypocenter hypocenter, double lat, double lon, double depth, List<ExactPickedEvent> pickedEvents,
                                  HypocenterFinderSettings finderSettings, HypocenterFinderThreadData threadData) {
        analyseHypocenter(hypocenter, lat, lon, depth, pickedEvents, finderSettings, threadData);
    }

    public static void analyseHypocenter(PreliminaryHypocenter hypocenter, double lat, double lon, double depth, List<ExactPickedEvent> events, HypocenterFinderSettings finderSettings, HypocenterFinderThreadData threadData) {
        int c = 0;

        for (ExactPickedEvent event : events) {
            double travelTime = TauPTravelTimeCalculator.getPWaveTravelTimeFast(depth, TauPTravelTimeCalculator.toAngle(event.distGC));
            if (travelTime == TauPTravelTimeCalculator.NO_ARRIVAL) {
                return;
            }

            travelTime += getElevationCorrection(event.elevation());

            long origin = event.pWave() - ((long) (travelTime * 1000));
            threadData.origins[c] = origin;
            c++;
        }

        long bestOrigin;
        if (USE_MEDIAN_FOR_ORIGIN) {
            Arrays.sort(threadData.origins);
            bestOrigin = threadData.origins[(threadData.origins.length - 1) / 2];
        } else {
            bestOrigin = threadData.origins[0];
        }

        double err = 0;
        int acc = 0;

        for (long orign : threadData.origins) {
            double _err = Math.abs(orign - bestOrigin);
            if (_err < finderSettings.pWaveInaccuracyThreshold()) {
                acc++;
            } else {
                _err = finderSettings.pWaveInaccuracyThreshold();
            }

            err += _err * _err;
        }

        hypocenter.lat = lat;
        hypocenter.lon = lon;
        hypocenter.depth = depth;
        hypocenter.origin = bestOrigin;
        hypocenter.err = err;
        hypocenter.correctStations = acc;
    }

    public static double getElevationCorrection(double elevation) {
        return elevation / 6000.0;
    }

    private void calculateDistances(List<ExactPickedEvent> pickedEvents, double lat, double lon) {
        for (ExactPickedEvent event : pickedEvents) {
            event.distGC = GeoUtils.greatCircleDistance(event.lat(),
                    event.lon(), lat, lon);
        }
    }

    public static final class ExactPickedEvent extends PickedEvent {
        public double distGC;

        public ExactPickedEvent(PickedEvent pickedEvent) {
            super(pickedEvent.pWave(), pickedEvent.lat(), pickedEvent.lon(), pickedEvent.elevation(), pickedEvent.maxRatio());
        }

    }

    private double getUniversalResolutionMultiplier(HypocenterFinderSettings finderSettings) {
        // 30% when 0.0 (min) selected
        // 100% when 40.0 (default) selected
        // 550% when 100 (max) selected
        double x = finderSettings.resolution();
        return ((x * x + 600) / 2200.0);
    }

    private HypocenterCondition checkConditions(List<PickedEvent> events, Hypocenter bestHypocenter, Hypocenter previousHypocenter, Cluster cluster) {
        if (bestHypocenter == null) {
            return HypocenterCondition.NULL;
        }
        double distFromRoot = GeoUtils.greatCircleDistance(bestHypocenter.lat, bestHypocenter.lon, cluster.getRootLat(),
                cluster.getRootLon());
        if (distFromRoot > 2000 && bestHypocenter.correctStations < 8) {
            return HypocenterCondition.DISTANT_EVENT_NOT_ENOUGH_STATIONS;
        }

        if (bestHypocenter.correctStations < 4) {
            return HypocenterCondition.NOT_ENOUGH_CORRECT_STATIONS;
        }
        if (checkQuadrants(bestHypocenter, events) < (distFromRoot > 4000 ? 1 : distFromRoot > 1000 ? 2 : 3)) {
            return HypocenterCondition.TOO_SHALLOW_ANGLE;
        }

        PreliminaryHypocenter bestPrelim = toPreliminary(bestHypocenter);

        if (selectBetterHypocenter(toPreliminary(previousHypocenter), bestPrelim) != bestPrelim) {
            return HypocenterCondition.PREVIOUS_WAS_BETTER;
        }

        return HypocenterCondition.OK;
    }

    private PreliminaryHypocenter toPreliminary(Hypocenter previousHypocenter) {
        if (previousHypocenter == null) {
            return null;
        }
        return new PreliminaryHypocenter(previousHypocenter.lat, previousHypocenter.lon, previousHypocenter.depth, previousHypocenter.origin, previousHypocenter.totalErr, previousHypocenter.correctStations);
    }

    private void updateHypocenter(Cluster cluster, Hypocenter bestHypocenter) {
        Earthquake earthquake = new Earthquake(cluster, bestHypocenter.lat, bestHypocenter.lon, bestHypocenter.depth,
                bestHypocenter.origin);
        earthquake.setPct(100.0 * bestHypocenter.getCorrectness());

        if (cluster.getEarthquake() == null) {
            if (!testing) {
                Sounds.playSound(Sounds.incoming);
                getEarthquakes().add(earthquake);
            }
            cluster.setEarthquake(earthquake);
        } else {
            cluster.getEarthquake().update(earthquake);
        }
        if (!testing) {
            earthquake.uppdateRegion();
        }

        double distFromAnchor = GeoUtils.greatCircleDistance(bestHypocenter.lat, bestHypocenter.lon,
                cluster.getAnchorLat(), cluster.getAnchorLon());
        if (distFromAnchor > 400) {
            cluster.updateAnchor(bestHypocenter);
        }

        cluster.revisionID += 1;
        cluster.getEarthquake().setRevisionID(cluster.revisionID);
    }

    private int checkQuadrants(Hypocenter hyp, List<PickedEvent> events) {
        int[] qua = new int[QUADRANTS];
        int good = 0;
        for (PickedEvent event : events) {
            double angle = GeoUtils.calculateAngle(hyp.lat, hyp.lon, event.lat(), event.lon());
            int q = (int) ((angle * QUADRANTS) / 360.0);
            if (qua[q] == 0) {
                good++;
            }
            qua[q]++;
        }
        return good;
    }

    private void calculateMagnitude(Earthquake earthquake) {
        if (earthquake.getCluster() == null) {
            return;
        }
        Collection<Event> goodEvents = earthquake.getCluster().getAssignedEvents().values();
        if (goodEvents.isEmpty()) {
            return;
        }
        ArrayList<Double> mags = new ArrayList<>();
        for (Event event : goodEvents) {
            if(!event.isValid()){
                continue;
            }
            double distGC = GeoUtils.greatCircleDistance(earthquake.getLat(), earthquake.getLon(),
                    event.getLatFromStation(), event.getLonFromStation());
            double distGE = GeoUtils.geologicalDistance(earthquake.getLat(), earthquake.getLon(),
                    -earthquake.getDepth(), event.getLatFromStation(), event.getLonFromStation(), event.getAnalysis().getStation().getAlt() / 1000.0);
            long expectedSArrival = (long) (earthquake.getOrigin()
                    + TauPTravelTimeCalculator.getSWaveTravelTime(earthquake.getDepth(), TauPTravelTimeCalculator.toAngle(distGC))
                    * 1000);
            long lastRecord = ((BetterAnalysis) event.getAnalysis()).getLatestLogTime();
            // *0.5 because s wave is stronger
            double mul = lastRecord > expectedSArrival + 8 * 1000 ? 1 : Math.max(1, 2.0 - distGC / 400.0);
            mags.add(IntensityTable.getMagnitude(distGE, event.getMaxRatio() * mul));
        }
        Collections.sort(mags);
        synchronized (earthquake.magsLock) {
            earthquake.setMags(mags);
            earthquake.setMag(mags.get((int) ((mags.size() - 1) * 0.5)));
        }
    }

    public static final int[] STORE_TABLE = {
            3, 3, // M0
            3, 3, // M1
            3, 3, // M2
            5, 6, // M3
            8, 16, // M4
            30, 30, // M5
            30, 30, // M6
            60, 60, // M7+
    };

    public void second() {
        Iterator<Earthquake> it = earthquakes.iterator();
        List<Earthquake> toBeRemoved = new ArrayList<>();
        while (it.hasNext()) {
            Earthquake earthquake = it.next();
            int store_minutes = STORE_TABLE[Math.max(0,
                    Math.min(STORE_TABLE.length - 1, (int) (earthquake.getMag() * 2.0)))];
            if (System.currentTimeMillis() - earthquake.getOrigin() > (long) store_minutes * 60 * 1000
                    && System.currentTimeMillis() - earthquake.getLastUpdate() > 0.25 * store_minutes * 60 * 1000) {
                if(GlobalQuake.instance != null) {
                    GlobalQuake.instance.getArchive().archiveQuakeAndSave(earthquake);
                }
                toBeRemoved.add(earthquake);
            }
        }
        earthquakes.removeAll(toBeRemoved);
    }

}
