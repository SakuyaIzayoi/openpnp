package org.openpnp.machine.reference;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.ReferencePnpJobProcessor.BoardLocationFiducialCheck;
import org.openpnp.machine.reference.ReferencePnpJobProcessor.Plan;
import org.openpnp.machine.reference.ReferencePnpJobProcessor.Step;
import org.openpnp.machine.reference.wizards.ReferencePasteJobProcessorWizard;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Panel;
import org.openpnp.model.Placement;
import org.openpnp.spi.FiducialLocator;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PnpJobPlanner;
import org.openpnp.spi.JobProcessor.JobProcessorException;
import org.openpnp.spi.PnpJobPlanner.PlannedPlacement;
import org.openpnp.spi.PnpJobProcessor.JobPlacement;
import org.openpnp.spi.PnpJobProcessor.JobPlacement.Status;
import org.openpnp.spi.base.AbstractJobProcessor;
import org.openpnp.spi.base.AbstractMachine;
import org.openpnp.spi.base.AbstractPnpJobProcessor;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.Utils2D;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

public class ReferencePasteJobProcessor extends AbstractPnpJobProcessor {
    interface Step {
        public Step step() throws JobProcessorException;
    }

    public enum JobOrderHint {
        Part
    }

    @Attribute(required = false)
    protected JobOrderHint jobOrder = JobOrderHint.Part;

    @Element(required = false)
    // public PnpJobPlanner planner = new SimplePastePlanner();
    public PnpJobPlanner planner = new ReferencePnpJobProcessor.TrivialPnpJobPlanner();

    protected Job job;

    protected Machine machine;

    protected Head head;

    protected List<JobPlacement> jobPlacements = new ArrayList<>();

    private Step currentStep = null;
    
    private double dispenseAmount = 50.0;
    private double retractAmount = 20.0;

    long startTime;
    int totalPasteDispensed;

    public ReferencePasteJobProcessor() {}

    public synchronized void initialize(Job job) throws Exception {
        if (job == null) {
            throw new Exception("Can't initialize with a null job.");
        }
        this.job = job;
        currentStep = new PreFlight();
        this.fireJobState(Configuration.get().getMachine().getSignalers(),
                AbstractJobProcessor.State.STOPPED);
    }

    @Override
    public synchronized boolean next() throws JobProcessorException {
        this.fireJobState(Configuration.get().getMachine().getSignalers(),
                AbstractJobProcessor.State.RUNNING);
        try {
            currentStep = currentStep.step();
        }
        catch (Exception e) {
            this.fireJobState(Configuration.get().getMachine().getSignalers(),
                    AbstractJobProcessor.State.ERROR);
            throw e;
        }
        if (currentStep == null) {
            this.fireJobState(Configuration.get().getMachine().getSignalers(),
                    AbstractJobProcessor.State.FINISHED);
        }
        return currentStep != null;
    }

    @Override
    public synchronized void abort() throws JobProcessorException {
        try {
            new Cleanup().step();
        }
        catch (Exception e) {
            Logger.error(e);
        }
        this.fireJobState(Configuration.get().getMachine().getSignalers(),
                AbstractJobProcessor.State.STOPPED);
        currentStep = null;
    }
    
    @Override
    public Wizard getConfigurationWizard() {
        return new ReferencePasteJobProcessorWizard((AbstractMachine) machine, this);
    }

    protected class PreFlight implements Step {
        public Step step() throws JobProcessorException {
            startTime = System.currentTimeMillis();
            totalPasteDispensed = 0;

            jobPlacements.clear();

            machine = Configuration.get().getMachine();
            try {
                head = machine.getDefaultHead();
            }
            catch (Exception e) {
                throw new JobProcessorException(machine, e);
            }

            checkSetupErrors();

            return new PanelFiducialCheck();
        }

        private void checkSetupErrors() throws JobProcessorException {
            fireTextStatus("Checking job for setup errors.");

            for (BoardLocation boardLocation : job.getBoardLocations()) {
                // Only check enabled boards
                if (!boardLocation.isEnabled()) {
                    continue;
                }

                checkDuplicateRefs(boardLocation);

                for (Placement placement : boardLocation.getBoard()
                                                        .getPlacements()) {
                    // Ignore placements that aren't placements
                    if (placement.getType() != Placement.Type.Placement) {
                        continue;
                    }

                    if (!placement.isEnabled()) {
                        continue;
                    }

                    // Ignore placements that are placed already
                    if (boardLocation.getPlaced(placement.getId())) {
                        continue;
                    }

                    // Ignore placements that aren't on the side of the board we're processing.
                    if (placement.getSide() != boardLocation.getSide()) {
                        continue;
                    }

                    JobPlacement jobPlacement = new JobPlacement(boardLocation, placement);

                    jobPlacements.add(jobPlacement);
                }
            }
        }

        private void checkDuplicateRefs(BoardLocation boardLocation) throws JobProcessorException {
            // Check for ID duplicates - throw error if any are found
            HashSet<String> idlist = new HashSet<String>();
            for (Placement placement : boardLocation.getBoard().getPlacements()) {
                if (idlist.contains(placement.getId())) {
                    throw new JobProcessorException(boardLocation,
                            String.format("This board contains at least one duplicate ID entry: %s ",
                                    placement.getId()));
                }
                else {
                    idlist.add(placement.getId());
                }
            }

        }
    }

    protected class PanelFiducialCheck implements Step {
        public Step step() throws JobProcessorException {
            FiducialLocator locator = Configuration.get().getMachine().getFiducialLocator();

            if (job.isUsingPanel() && job.getPanels().get(0).isCheckFiducials()) {
                Panel p = job.getPanels().get(0);

                BoardLocation boardLocation = job.getBoardLocations().get(0);

                fireTextStatus("Panel fiducial check on %s", boardLocation);
                try {
                    locator.locateBoard(boardLocation, p.isCheckFiducials());
                }
                catch (Exception e) {
                    throw new JobProcessorException(boardLocation, e);
                }
            }

            return new BoardLocationFiducialCheck();
        }
    }

    protected class BoardLocationFiducialCheck implements Step {
        protected Set<BoardLocation> completed = new HashSet<>();

        public Step step() throws JobProcessorException {
            FiducialLocator locator = Configuration.get().getMachine().getFiducialLocator();

            for (BoardLocation boardLocation : job.getBoardLocations()) {
                if (!boardLocation.isEnabled()) {
                    continue;
                }
                if (!boardLocation.isCheckFiducials()) {
                    continue;
                }
                if (completed.contains(boardLocation)) {
                    continue;
                }

                fireTextStatus("Fiducial check for %s", boardLocation);
                try {
                    locator.locateBoard(boardLocation);
                }
                catch (Exception e) {
                    throw new JobProcessorException(boardLocation, e);
                }

                completed.add(boardLocation);
                return this;
            }

            return new Plan();
        }
    }

    protected class Plan implements Step {
        public Step step() throws JobProcessorException {
            fireTextStatus("Planning paste placements.");

            List<JobPlacement> jobPlacements;
            jobPlacements = getPendingJobPlacements().stream()
                                                     .sorted(Comparator.comparing(
                                                             JobPlacement::getPartId))
                                                     .collect(Collectors.toList());

            if (jobPlacements.isEmpty()) {
                return new Finish();
            }

            long t = System.currentTimeMillis();
            List<PlannedPlacement> plannedPlacements = planner.plan(head, jobPlacements);
            Logger.debug("Planner complete in {}ms: {}", (System.currentTimeMillis() - t),
                    plannedPlacements);

            if (plannedPlacements.isEmpty()) {
                throw new JobProcessorException(planner,
                        "Planner failed to plan any placements. Please contact support.");
            }

            for (PlannedPlacement plannedPlacement : plannedPlacements) {
                plannedPlacement.jobPlacement.setStatus(Status.Processing);
            }

            Logger.debug("Planned placements {}", plannedPlacements);

            return new Dispense(plannedPlacements);
        }
    }

    protected class Dispense extends PlannedPlacementStep {
        public Dispense(List<PlannedPlacement> plannedPlacements) {
            super(plannedPlacements);
        }

        @Override
        public Step stepImpl(PlannedPlacement plannedPlacement) throws JobProcessorException {
            if (plannedPlacement == null) {
                return new FinishCycle();
            }

            final Nozzle nozzle = plannedPlacement.nozzle;
            final JobPlacement jobPlacement = plannedPlacement.jobPlacement;
            final Placement placement = jobPlacement.getPlacement();
            final BoardLocation boardLocation = plannedPlacement.jobPlacement.getBoardLocation();

            Location placementLocation = getPlacementLocation(plannedPlacement);

            dispense(nozzle, placement, placementLocation);

            jobPlacement.setStatus(Status.Complete);

            boardLocation.setPlaced(jobPlacement.getPlacement().getId(), true);

            totalPasteDispensed++;

            return this;
        }

        private void dispense(Nozzle nozzle, Placement placement, Location placementLocation)
                throws JobProcessorException {
            fireTextStatus("Placing %s.", placement.getId());

            try {
                MovableUtils.moveToLocationAtSafeZ(nozzle, placementLocation);

                nozzle.moveTo(placementLocation);
                
                // Dispense
                Location dispenseOffset = new Location(LengthUnit.Millimeters, 0.0, 0.0, 0.0, dispenseAmount);
                nozzle.moveTo(placementLocation.add(dispenseOffset));
                
                // Retract
                Location retractionOffset = new Location(LengthUnit.Millimeters, 0.0, 0.0, 0.0, -retractAmount);
                nozzle.moveTo(nozzle.getLocation().add(retractionOffset));

                nozzle.moveToSafeZ();
            }
            catch (Exception e) {
                throw new JobProcessorException(nozzle, e);
            }
        }

        private Location getPlacementLocation(PlannedPlacement plannedPlacement) {
            final JobPlacement jobPlacement = plannedPlacement.jobPlacement;
            final Placement placement = jobPlacement.getPlacement();
            final BoardLocation boardLocation = plannedPlacement.jobPlacement.getBoardLocation();

            Location location =
                    Utils2D.calculateBoardPlacementLocation(boardLocation, placement.getLocation());

            return location;
        }
    }

    protected class FinishCycle implements Step {
        public Step step() throws JobProcessorException {
            return new Plan();
        }
    }

    protected class Cleanup implements Step {
        public Step step() throws JobProcessorException {
            fireTextStatus("Cleaning up.");

            try {
                head.moveToSafeZ();
            }
            catch (Exception e) {
                throw new JobProcessorException(head, e);
            }

            fireTextStatus("Park head.");
            try {
                MovableUtils.park(head);
            }
            catch (Exception e) {
                throw new JobProcessorException(head, e);
            }

            return null;
        }
    }

    protected class Finish implements Step {
        public Step step() throws JobProcessorException {
            new Cleanup().step();

            double dtSec = (System.currentTimeMillis() - startTime) / 1000.0;
            DecimalFormat df = new DecimalFormat("###,###.0");

            Logger.info("Job finished {} placements in {} sec. This is {} PPH", totalPasteDispensed,
                    df.format(dtSec), df.format(totalPasteDispensed / (dtSec / 3600.0)));

            try {
                HashMap<String, Object> params = new HashMap<>();
                params.put("job", job);
                params.put("jobProcessor", this);
                Configuration.get().getScripting().on("Job.Finished", params);
            }
            catch (Exception e) {
                throw new JobProcessorException(null, e);
            }

            return null;
        }
    }

    protected class Abort implements Step {
        public Step step() throws JobProcessorException {
            new Cleanup().step();

            fireTextStatus("Aborted.");

            return null;
        }
    }

    protected List<JobPlacement> getPendingJobPlacements() {
        return this.jobPlacements.stream()
                                 .filter((jobPlacement) -> {
                                     return jobPlacement.getStatus() == Status.Pending;
                                 }).collect(Collectors.toList());
    }

    protected boolean isJobComplete() {
        return getPendingJobPlacements().isEmpty();
    }

    protected abstract class PlannedPlacementStep implements Step {
        protected final List<PlannedPlacement> plannedPlacements;
        private Set<PlannedPlacement> completed = new HashSet<>();

        protected PlannedPlacementStep(List<PlannedPlacement> plannedPlacements) {
            this.plannedPlacements = plannedPlacements;
        }

        protected abstract Step stepImpl(PlannedPlacement plannedPlacement)
                throws JobProcessorException;

        public Step step() throws JobProcessorException {
            PlannedPlacement plannedPlacement = plannedPlacements.stream()
                    .filter(p -> {return p.jobPlacement.getStatus() == Status.Processing;})
                    .filter(p -> {return !completed.contains(p);})
                    .findFirst().orElse(null);
            try {
                Step result = stepImpl(plannedPlacement);
                completed.add(plannedPlacement);
                return result;
            }
            catch (JobProcessorException e) {
                switch (plannedPlacement.jobPlacement.getPlacement()
                                                     .getErrorHandling()) {
                    case Alert:
                        throw e;
                    case Defer:
                        plannedPlacement.jobPlacement.setError(e);
                        return this;
                    default:
                        throw new Error("Unhandled Error handling case "
                                + plannedPlacement.jobPlacement.getPlacement()
                                                               .getErrorHandling());
                }
            }

        }
    }
    
    public double getDispenseAmount() {
        return dispenseAmount;
    }
    
    public void setDispenseAmount(double dispenseAmount) {
        this.dispenseAmount = dispenseAmount;
    }
    
    public double getRetractAmount() {
        return retractAmount;
    }
    
    public void setRetractAmount(double retractAmount) {
        this.retractAmount = retractAmount;
    }
}
