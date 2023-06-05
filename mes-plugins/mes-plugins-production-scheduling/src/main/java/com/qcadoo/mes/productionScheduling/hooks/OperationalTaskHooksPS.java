package com.qcadoo.mes.productionScheduling.hooks;

import com.qcadoo.localization.api.TranslationService;
import com.qcadoo.mes.basic.ShiftsService;
import com.qcadoo.mes.basicProductionCounting.BasicProductionCountingService;
import com.qcadoo.mes.operationTimeCalculations.OperationWorkTime;
import com.qcadoo.mes.operationTimeCalculations.OperationWorkTimeService;
import com.qcadoo.mes.orders.OperationalTasksService;
import com.qcadoo.mes.orders.constants.OperationalTaskFields;
import com.qcadoo.mes.orders.hooks.OperationalTaskHooks;
import com.qcadoo.mes.orders.hooks.WorkstationChangeoverForOperationalTaskHooks;
import com.qcadoo.mes.orders.services.WorkstationChangeoverService;
import com.qcadoo.mes.orders.validators.OperationalTaskValidators;
import com.qcadoo.mes.productionLines.constants.WorkstationFieldsPL;
import com.qcadoo.mes.timeNormsForOperations.NormService;
import com.qcadoo.mes.timeNormsForOperations.constants.TechOperCompWorkstationTimeFields;
import com.qcadoo.mes.timeNormsForOperations.constants.TechnologyOperationComponentFieldsTNFO;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.Entity;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
public class OperationalTaskHooksPS {

    @Autowired
    private TranslationService translationService;

    @Autowired
    private ShiftsService shiftsService;

    @Autowired
    private NormService normService;
    @Autowired
    private OperationalTasksService operationalTasksService;
    @Autowired
    private OperationWorkTimeService operationWorkTimeService;

    @Autowired
    private BasicProductionCountingService basicProductionCountingService;

    @Autowired
    private OperationalTaskHooks operationalTaskHooks;

    @Autowired
    private OperationalTaskValidators operationalTaskValidators;

    @Autowired
    private WorkstationChangeoverService workstationChangeoverService;

    @Autowired
    private WorkstationChangeoverForOperationalTaskHooks workstationChangeoverForOperationalTaskHooks;

    public void onSave(final DataDefinition operationalTaskDD, final Entity operationalTask) {
        setDates(operationalTaskDD, operationalTask, true, true);
        setStaff(operationalTaskDD, operationalTask, true, true);
    }

    private void setDates(final DataDefinition operationalTaskDD, final Entity operationalTask,
                          final boolean includeTpz, final boolean includeAdditionalTime) {
        Entity workstation = operationalTask.getBelongsToField(OperationalTaskFields.WORKSTATION);
        Date dateFrom = operationalTask.getDateField(OperationalTaskFields.START_DATE);
        Date dateTo = operationalTask.getDateField(OperationalTaskFields.FINISH_DATE);

        Long operationalTaskId = operationalTask.getId();

        Entity operationalTaskFromDB;
        Entity workstationFromDB = null;
        Date finishDateFromDB = null;

        if (Objects.nonNull(operationalTaskId)) {
            operationalTaskFromDB = operationalTask.getDataDefinition().get(operationalTask.getId());

            finishDateFromDB = operationalTaskFromDB.getDateField(OperationalTaskFields.FINISH_DATE);
            workstationFromDB = operationalTaskFromDB.getBelongsToField(OperationalTaskFields.WORKSTATION);
        }

        setDates(operationalTaskDD, operationalTask, workstation, workstationFromDB, dateFrom, dateTo, finishDateFromDB,
                includeTpz, includeAdditionalTime);
    }

    public SetDateResult setDates(final DataDefinition operationalTaskDD, final Entity operationalTask,
                                  final Entity workstation, final Entity workstationFromDB,
                                  final Date dateFrom, final Date dateTo, final Date finishDateFromDB,
                                  final boolean includeTpz, final boolean includeAdditionalTime) {
        SetDateResult setDateResult = SetDateResult.NONE;

        Entity productionLine = null;

        if (Objects.nonNull(workstation)) {
            productionLine = workstation.getBelongsToField(WorkstationFieldsPL.PRODUCTION_LINE);
        }

        Date startDate = null;

        long changeOverDuration = 0;

        if (Objects.nonNull(dateFrom)) {
            List<Entity> workstationChangeoverForOperationalTasks = operationalTask.getHasManyField(OperationalTaskFields.CURRENT_WORKSTATION_CHANGEOVER_FOR_OPERATIONAL_TASKS);

            Optional<Date> mayBeChangeoversMaxFinishDate = workstationChangeoverService.getWorkstationChangeoversMaxFinishDate(workstationChangeoverForOperationalTasks);

            DateTime newStartDateTime = mayBeChangeoversMaxFinishDate.map(DateTime::new).orElseGet(() -> new DateTime(dateFrom));

            changeOverDuration = newStartDateTime.toDate().getTime() - dateFrom.getTime();

            startDate = shiftsService.getNearestWorkingDate(newStartDateTime, productionLine).orElse(newStartDateTime).toDate();

            if (startDate.compareTo(newStartDateTime.toDate()) != 0) {
                setDateResult = SetDateResult.START_DATE_NEAREST;
            }
        }

        boolean workstationChanged = (Objects.isNull(workstation) ? Objects.nonNull(workstationFromDB)
                : (Objects.nonNull(workstationFromDB) && !workstation.getId().equals(workstationFromDB.getId())));

        Date finishDate = null;

        if (Objects.nonNull(dateTo)) {
            finishDate = dateTo;
        } else if (workstationChanged) {
            finishDate = finishDateFromDB;
        }

        finishDate = Date.from(finishDate.toInstant().plusSeconds(changeOverDuration / 1000));

        Entity technologyOperationComponent = operationalTask.getBelongsToField(OperationalTaskFields.TECHNOLOGY_OPERATION_COMPONENT);

        if (Objects.nonNull(technologyOperationComponent)) {
            Entity order = operationalTask.getBelongsToField(OperationalTaskFields.ORDER);

            Entity parent = operationalTasksService.getParent(technologyOperationComponent, order);
            List<Entity> children = operationalTasksService.getChildren(technologyOperationComponent, order);

            Optional<String> startErrorResponse = checkStartDate(dateFrom, startDate, parent, children);

            if (startErrorResponse.isPresent()) {
                String message = startErrorResponse.get();

                operationalTask.addError(operationalTaskDD.getField(OperationalTaskFields.START_DATE), message);

                setDateResult = SetDateResult.ERROR;
            }

            if (Objects.nonNull(dateTo) || workstationChanged) {
                DateTime newFinishDateTime = new DateTime(finishDate);

                if (Objects.nonNull(dateFrom)) {
                    if (workstationChanged) {
                        finishDate = getFinishDate(operationalTask, technologyOperationComponent, workstation, startDate, includeTpz, includeAdditionalTime);
                    } else {
                        Date nearestWorkingDate = shiftsService.getNearestWorkingDate(newFinishDateTime, productionLine)
                                .orElse(newFinishDateTime).toDate();

                        if (finishDate.getTime() != nearestWorkingDate.getTime()) {
                            finishDate = getFinishDate(operationalTask, technologyOperationComponent, workstation, startDate, includeTpz, includeAdditionalTime);

                            if (SetDateResult.START_DATE_NEAREST.equals(setDateResult)) {
                                setDateResult = SetDateResult.BOTH;
                            } else {
                                setDateResult = SetDateResult.FINISH_DATE_NEAREST;
                            }
                        }
                    }
                }

                Optional<String> finishErrorResponse = checkFinishDate(finishDate, parent, children);

                if (finishErrorResponse.isPresent()) {
                    String message = finishErrorResponse.get();

                    operationalTask.addError(operationalTaskDD.getField(OperationalTaskFields.FINISH_DATE), message);

                    setDateResult = SetDateResult.ERROR;
                }
            }
        }

        if (Objects.nonNull(dateFrom)) {
            operationalTask.setField(OperationalTaskFields.START_DATE, startDate);
        }

        if (Objects.nonNull(dateTo) || workstationChanged) {
            operationalTask.setField(OperationalTaskFields.FINISH_DATE, finishDate);
        }

        if (Objects.nonNull(dateFrom) || Objects.nonNull(dateTo) || workstationChanged) {
            List<Entity> workstationChangeoverForOperationalTasks = operationalTask.getHasManyField(OperationalTaskFields.CURRENT_WORKSTATION_CHANGEOVER_FOR_OPERATIONAL_TASKS);

            workstationChangeoverForOperationalTasks.forEach(workstationChangeoverForOperationalTask -> {
                workstationChangeoverForOperationalTaskHooks.validatesWith(workstationChangeoverForOperationalTask.getDataDefinition(), workstationChangeoverForOperationalTask);
            });

            boolean isValid = workstationChangeoverForOperationalTasks.stream().allMatch(Entity::isValid);

            if (isValid && operationalTaskValidators.datesAreInCorrectOrder(operationalTaskDD, operationalTask) && operationalTaskValidators.datesAreCorrect(operationalTaskDD, operationalTask)) {
                operationalTaskHooks.changeDateInOrder(operationalTask);
            }
        }

        return setDateResult;
    }

    public Optional<String> checkStartDate(final Date dateFrom, final Date startDate, final Entity parent,
                                           final List<Entity> children) {
        if (Objects.nonNull(dateFrom)) {
            if (Objects.nonNull(parent) && Objects.nonNull(parent.getBelongsToField(OperationalTaskFields.WORKSTATION))
                    && parent.getDateField(OperationalTaskFields.START_DATE).before(startDate)) {
                return Optional.of(translationService.translate(
                        "orders.operationalTask.error.inappropriateStartDateNext", LocaleContextHolder.getLocale()));
            }

            for (Entity child : children) {
                if (child.getBelongsToField(OperationalTaskFields.WORKSTATION) != null
                        && child.getDateField(OperationalTaskFields.START_DATE).after(startDate)) {
                    return Optional.of(translationService.translate(
                            "orders.operationalTask.error.inappropriateStartDatePrevious", LocaleContextHolder.getLocale()));
                }
            }
        }

        return Optional.empty();
    }

    public Optional<String> checkFinishDate(final Date finishDate, final Entity parent, final List<Entity> children) {
        if (Objects.nonNull(parent) && Objects.nonNull(parent.getBelongsToField(OperationalTaskFields.WORKSTATION))
                && parent.getDateField(OperationalTaskFields.FINISH_DATE).before(finishDate)) {
            return Optional.of(translationService.translate(
                    "orders.operationalTask.error.inappropriateFinishDateNext", LocaleContextHolder.getLocale()));
        }

        for (Entity child : children) {
            if (Objects.nonNull(child.getBelongsToField(OperationalTaskFields.WORKSTATION))
                    && child.getDateField(OperationalTaskFields.FINISH_DATE).after(finishDate)) {
                return Optional.of(translationService.translate(
                        "orders.operationalTask.error.inappropriateFinishDatePrevious", LocaleContextHolder.getLocale()));
            }
        }

        return Optional.empty();
    }

    public Date getFinishDate(final Entity operationalTask, final Entity technologyOperationComponent, final Entity workstation,
                              final Date startDate, final boolean includeTpz, final boolean includeAdditionalTime) {
        Entity order = operationalTask.getBelongsToField(OperationalTaskFields.ORDER);

        Optional<Entity> techOperCompWorkstationTime = normService.getTechOperCompWorkstationTime(technologyOperationComponent, workstation);

        BigDecimal staffFactor = normService.getStaffFactor(technologyOperationComponent, operationalTask.getIntegerField(OperationalTaskFields.ACTUAL_STAFF));
        BigDecimal operationComponentRuns = basicProductionCountingService.getOperationComponentRuns(order, technologyOperationComponent);

        Integer machineWorkTime;
        Integer additionalTime;

        if (techOperCompWorkstationTime.isPresent()) {
            OperationWorkTime operationWorkTime = operationWorkTimeService.estimateTechOperationWorkTimeForWorkstation(
                    technologyOperationComponent,
                    operationComponentRuns, includeTpz, false,
                    techOperCompWorkstationTime.get(), staffFactor);

            machineWorkTime = operationWorkTime.getMachineWorkTime();
            additionalTime = techOperCompWorkstationTime.get()
                    .getIntegerField(TechOperCompWorkstationTimeFields.TIME_NEXT_OPERATION);
        } else {
            OperationWorkTime operationWorkTime = operationWorkTimeService.estimateOperationWorkTime(null,
                    technologyOperationComponent,
                    operationComponentRuns, includeTpz, false,
                    false, staffFactor);

            machineWorkTime = operationWorkTime.getMachineWorkTime();
            additionalTime = technologyOperationComponent
                    .getIntegerField(TechnologyOperationComponentFieldsTNFO.TIME_NEXT_OPERATION);
        }

        Entity productionLine = null;

        if (Objects.nonNull(workstation)) {
            productionLine = workstation.getBelongsToField(WorkstationFieldsPL.PRODUCTION_LINE);
        }

        Date finishDate = shiftsService.findDateToForProductionLine(startDate, machineWorkTime, productionLine);

        if (includeAdditionalTime) {
            finishDate = Date.from(finishDate.toInstant().plusSeconds(additionalTime));
        }

        return finishDate;
    }

    public void setStaff(final DataDefinition operationalTaskDD, final Entity operationalTask,
                         final boolean includeTpz, final boolean includeAdditionalTime) {
        Entity technologyOperationComponent = operationalTask
                .getBelongsToField(OperationalTaskFields.TECHNOLOGY_OPERATION_COMPONENT);

        int optimalStaff = getOptimalStaff(technologyOperationComponent);

        Integer actualStaff = operationalTask.getIntegerField(OperationalTaskFields.ACTUAL_STAFF);

        if (Objects.isNull(actualStaff)) {
            actualStaff = optimalStaff;

            operationalTask.setField(OperationalTaskFields.ACTUAL_STAFF, actualStaff);
        }

        List<Entity> workers = operationalTask.getManyToManyField(OperationalTaskFields.WORKERS);

        Entity staff = operationalTask.getBelongsToField(OperationalTaskFields.STAFF);
        Entity operationalTaskDB = null;

        if (Objects.nonNull(operationalTask.getId())) {
            operationalTaskDB = operationalTaskDD.get(operationalTask.getId());
        }

        if (workers.size() > 1 || workers.isEmpty() && Objects.nonNull(operationalTaskDB)
                && !operationalTaskDB.getManyToManyField(OperationalTaskFields.WORKERS).isEmpty()) {
            operationalTask.setField(OperationalTaskFields.STAFF, null);
        } else if (Objects.nonNull(staff) && workers.size() <= 1) {
            operationalTask.setField(OperationalTaskFields.WORKERS, Collections.singletonList(staff));
        } else if (Objects.isNull(staff) && workers.size() == 1) {
            if (Objects.nonNull(operationalTaskDB) && operationalTaskDB.getManyToManyField(OperationalTaskFields.WORKERS).size() != 1) {
                operationalTask.setField(OperationalTaskFields.STAFF, workers.get(0));
            } else {
                operationalTask.setField(OperationalTaskFields.WORKERS, Collections.emptyList());
            }
        }

        updateFinishDate(operationalTask, technologyOperationComponent, actualStaff, operationalTaskDB, includeTpz, includeAdditionalTime);

        if (actualStaff != operationalTask.getManyToManyField(OperationalTaskFields.WORKERS).size()) {
            operationalTask.addGlobalMessage(
                    "orders.operationalTask.error.workersQuantityDifferentThanActualStaff");
        }
    }

    private int getOptimalStaff(final Entity technologyOperationComponent) {
        if (!Objects.isNull(technologyOperationComponent)) {
            return technologyOperationComponent.getIntegerField(TechnologyOperationComponentFieldsTNFO.OPTIMAL_STAFF);
        } else {
            return 1;
        }
    }

    private void updateFinishDate(final Entity operationalTask, final Entity technologyOperationComponent, final Integer actualStaff, final Entity operationalTaskDB,
                                  final boolean includeTpz, final boolean includeAdditionalTime) {
        if (!Objects.isNull(technologyOperationComponent) && technologyOperationComponent
                .getBooleanField(TechnologyOperationComponentFieldsTNFO.TJ_DECREASES_FOR_ENLARGED_STAFF) &&
                (Objects.isNull(operationalTask.getId()) && !actualStaff.equals(technologyOperationComponent.getIntegerField(TechnologyOperationComponentFieldsTNFO.MIN_STAFF))
                        || Objects.nonNull(operationalTaskDB) && actualStaff != operationalTaskDB.getIntegerField(OperationalTaskFields.ACTUAL_STAFF).intValue())) {
            Entity workstation = operationalTask.getBelongsToField(OperationalTaskFields.WORKSTATION);
            Date startDate = operationalTask.getDateField(OperationalTaskFields.START_DATE);

            operationalTask.setField(OperationalTaskFields.FINISH_DATE,
                    getFinishDate(operationalTask, technologyOperationComponent, workstation, startDate,
                            includeTpz, includeAdditionalTime));
        }
    }

}
