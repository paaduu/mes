package com.qcadoo.mes.productionScheduling.hooks;

import com.qcadoo.mes.basic.ParameterService;
import com.qcadoo.mes.basic.ShiftsService;
import com.qcadoo.mes.basic.constants.WorkstationFields;
import com.qcadoo.mes.operationTimeCalculations.OperationWorkTime;
import com.qcadoo.mes.operationTimeCalculations.OperationWorkTimeService;
import com.qcadoo.mes.orders.constants.*;
import com.qcadoo.mes.orders.services.WorkstationChangeoverService;
import com.qcadoo.mes.orders.validators.SchedulePositionValidators;
import com.qcadoo.mes.productionLines.constants.WorkstationFieldsPL;
import com.qcadoo.mes.technologies.constants.TechnologyOperationComponentFields;
import com.qcadoo.mes.timeNormsForOperations.NormService;
import com.qcadoo.mes.timeNormsForOperations.constants.TechOperCompWorkstationTimeFields;
import com.qcadoo.mes.timeNormsForOperations.constants.TechnologyOperationComponentFieldsTNFO;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.search.*;
import com.qcadoo.plugin.api.PluginManager;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.qcadoo.mes.orders.states.constants.OperationalTaskStateStringValues.FINISHED;
import static com.qcadoo.mes.orders.states.constants.OperationalTaskStateStringValues.REJECTED;
import static com.qcadoo.mes.timeNormsForOperations.constants.TechnologyOperationComponentFieldsTNFO.NEXT_OPERATION_AFTER_PRODUCED_TYPE;
import static com.qcadoo.mes.timeNormsForOperations.constants.TechnologyOperationComponentFieldsTNFO.SPECIFIED;
import static com.qcadoo.model.api.search.SearchProjections.*;

@Service
public class SchedulePositionHooksPS {

    private static final String L_DOT = ".";

    private static final String L_ID = "id";

    private static final String ORDERS_FOR_SUBPRODUCTS_GENERATION = "ordersForSubproductsGeneration";

    @Autowired
    private PluginManager pluginManager;

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private ParameterService parameterService;

    @Autowired
    private OperationWorkTimeService operationWorkTimeService;

    @Autowired
    private ShiftsService shiftsService;

    @Autowired
    private NormService normService;

    @Autowired
    private SchedulePositionValidators schedulePositionValidators;

    @Autowired
    private WorkstationChangeoverService workstationChangeoverService;

    public void onSave(final DataDefinition schedulePositionDD, final Entity schedulePosition) {
        setDates(schedulePositionDD, schedulePosition);
    }

    private void setDates(final DataDefinition schedulePositionD, final Entity schedulePosition) {
        Entity schedule = schedulePosition.getBelongsToField(SchedulePositionFields.SCHEDULE);
        Entity workstation = schedulePosition.getBelongsToField(SchedulePositionFields.WORKSTATION);
        Entity technologyOperationComponent = schedulePosition.getBelongsToField(SchedulePositionFields.TECHNOLOGY_OPERATION_COMPONENT);

        if (Objects.nonNull(workstation)) {
            BigDecimal staffFactor = normService.getStaffFactor(technologyOperationComponent, technologyOperationComponent.getIntegerField(TechnologyOperationComponentFieldsTNFO.OPTIMAL_STAFF));

            Integer machineWorkTime = schedulePosition.getIntegerField(SchedulePositionFields.MACHINE_WORK_TIME);
            Integer additionalTime = schedulePosition.getIntegerField(SchedulePositionFields.ADDITIONAL_TIME);

            Optional<Entity> techOperCompWorkstationTime = normService.getTechOperCompWorkstationTime(technologyOperationComponent, workstation);

            if (techOperCompWorkstationTime.isPresent()) {
                OperationWorkTime operationWorkTime = operationWorkTimeService.estimateTechOperationWorkTimeForWorkstation(
                        technologyOperationComponent,
                        schedulePosition.getDecimalField(SchedulePositionFields.OPERATION_RUNS),
                        schedule.getBooleanField(ScheduleFields.INCLUDE_TPZ), false, techOperCompWorkstationTime.get(),
                        staffFactor);

                machineWorkTime = operationWorkTime.getMachineWorkTime();
                additionalTime = techOperCompWorkstationTime.get()
                        .getIntegerField(TechOperCompWorkstationTimeFields.TIME_NEXT_OPERATION);
            }

            Date finishDate = getFinishDate(schedulePosition, workstation);
            finishDate = getFinishDateWithChildren(schedulePosition, finishDate);
            finishDate = getFinishDateWithChangeovers(schedulePosition, finishDate);

            DateTime finishDateTime = new DateTime(finishDate);

            Entity productionLine = workstation.getBelongsToField(WorkstationFieldsPL.PRODUCTION_LINE);

            Date newStartTime = shiftsService.getNearestWorkingDate(finishDateTime, productionLine).orElse(finishDateTime).toDate();
            Date newEndTime = shiftsService.findDateToForProductionLine(newStartTime, machineWorkTime, productionLine);

            if (schedule.getBooleanField(ScheduleFields.ADDITIONAL_TIME_EXTENDS_OPERATION)) {
                newEndTime = Date.from(newEndTime.toInstant().plusSeconds(additionalTime));
            }

            Date childrenEndTime = schedulePositionValidators.getChildrenMaxEndTime(schedulePosition);

            if (!Objects.isNull(childrenEndTime) && childrenEndTime.after(newEndTime)) {
                newEndTime = childrenEndTime;
            }

            schedulePosition.setField(SchedulePositionFields.START_TIME, newStartTime);
            schedulePosition.setField(SchedulePositionFields.END_TIME, newEndTime);
        }
    }

    private Date getFinishDate(final Entity schedule, final Entity workstation) {
        Date scheduleStartTime = schedule.getDateField(ScheduleFields.START_TIME);

        Date finishDate;

        if (schedule.getBooleanField(ScheduleFields.SCHEDULE_FOR_BUFFER)
                && workstation.getBooleanField(WorkstationFields.BUFFER)) {
            finishDate = scheduleStartTime;
        } else {
            Date operationalTasksMaxFinishDate = getOperationalTasksMaxFinishDateForWorkstation(scheduleStartTime,
                    workstation);

            if (Objects.nonNull(operationalTasksMaxFinishDate)) {
                finishDate = operationalTasksMaxFinishDate;
            } else {
                finishDate = scheduleStartTime;
            }
        }

        return finishDate;
    }

    private Date getOperationalTasksMaxFinishDateForWorkstation(final Date scheduleStartTime, final Entity workstation) {
        SearchCriteriaBuilder searchCriteriaBuilder = getOperationalTaskDD().find()
                .createAlias(OperationalTaskFields.WORKSTATION, OperationalTaskFields.WORKSTATION, JoinType.LEFT)
                .add(SearchRestrictions.eq(OperationalTaskFields.WORKSTATION + L_DOT + L_ID, workstation.getId()))
                .add(SearchRestrictions.ne(OperationalTaskFields.STATE, REJECTED));

        Entity parameter = parameterService.getParameter();

        if (parameter.getBooleanField(ParameterFieldsO.SKIP_FINISHED_TASKS)) {
            searchCriteriaBuilder.add(SearchRestrictions.ne(OperationalTaskFields.STATE, FINISHED));
        }

        Entity operationalTasksMaxFinishDateEntity = searchCriteriaBuilder.add(SearchRestrictions.gt(OperationalTaskFields.FINISH_DATE, scheduleStartTime))
                .setProjection(list()
                        .add(alias(SearchProjections.max(OperationalTaskFields.FINISH_DATE), OperationalTaskFields.FINISH_DATE))
                        .add(rowCount()))
                .addOrder(SearchOrders.desc(OperationalTaskFields.FINISH_DATE)).setMaxResults(1).uniqueResult();

        return operationalTasksMaxFinishDateEntity.getDateField(OperationalTaskFields.FINISH_DATE);
    }

    private Date getFinishDateWithChildren(final Entity schedulePosition, final Date finishDate) {
        Date childrenEndTime = getChildrenMaxEndTime(schedulePosition);

        if (!Objects.isNull(childrenEndTime) && childrenEndTime.after(finishDate)) {
            return childrenEndTime;
        }

        if (pluginManager.isPluginEnabled(ORDERS_FOR_SUBPRODUCTS_GENERATION)) {
            childrenEndTime = schedulePositionValidators.getOrdersChildrenMaxEndTime(schedulePosition);

            if (!Objects.isNull(childrenEndTime) && childrenEndTime.after(finishDate)) {
                return childrenEndTime;
            }
        }

        return finishDate;
    }

    private Date getChildrenMaxEndTime(final Entity schedulePosition) {
        Date childrenEndTime = null;

        Entity schedule = schedulePosition.getBelongsToField(SchedulePositionFields.SCHEDULE);

        boolean includeTpz = schedule.getBooleanField(ScheduleFields.INCLUDE_TPZ);

        List<Entity> children = schedule.getHasManyField(ScheduleFields.POSITIONS).stream()
                .filter(e -> e.getBelongsToField(SchedulePositionFields.ORDER).getId().equals(schedulePosition.getBelongsToField(SchedulePositionFields.ORDER).getId())
                        && Objects.nonNull(e.getBelongsToField(SchedulePositionFields.TECHNOLOGY_OPERATION_COMPONENT).getBelongsToField(TechnologyOperationComponentFields.PARENT))
                        && e.getBelongsToField(SchedulePositionFields.TECHNOLOGY_OPERATION_COMPONENT).getBelongsToField(TechnologyOperationComponentFields.PARENT).getId()
                        .equals(schedulePosition.getBelongsToField(SchedulePositionFields.TECHNOLOGY_OPERATION_COMPONENT).getId())).collect(Collectors.toList());

        for (Entity child : children) {
            Entity operationComponent = child.getBelongsToField(SchedulePositionFields.TECHNOLOGY_OPERATION_COMPONENT);

            Date childEndTime;

            if (SPECIFIED.equals(operationComponent.getStringField(NEXT_OPERATION_AFTER_PRODUCED_TYPE))) {
                Entity workstation = child.getBelongsToField(SchedulePositionFields.WORKSTATION);
                Entity productionLine = workstation.getBelongsToField(WorkstationFieldsPL.PRODUCTION_LINE);
                Integer machineWorkTime = getMachineWorkTime(includeTpz, child, operationComponent, workstation);

                childEndTime = shiftsService.findDateToForProductionLine(child.getDateField(SchedulePositionFields.START_TIME), machineWorkTime, productionLine);
            } else {
                if (!schedule.getBooleanField(ScheduleFields.ADDITIONAL_TIME_EXTENDS_OPERATION)) {
                    childEndTime = Date.from(child.getDateField(SchedulePositionFields.END_TIME).toInstant().plusSeconds(child.getIntegerField(SchedulePositionFields.ADDITIONAL_TIME)));
                } else {
                    childEndTime = child.getDateField(SchedulePositionFields.END_TIME);
                }
            }

            if (Objects.isNull(childrenEndTime) || childEndTime.after(childrenEndTime)) {
                childrenEndTime = childEndTime;
            }
        }

        return childrenEndTime;
    }

    private Integer getMachineWorkTime(final boolean includeTpz, final Entity child, final Entity operationComponent,
                                      final Entity workstation) {
        BigDecimal partialOperationComponentRuns = child.getDecimalField(SchedulePositionFields.PARTIAL_OPERATION_RUNS);
        BigDecimal staffFactor = normService.getStaffFactor(operationComponent, operationComponent.getIntegerField(TechnologyOperationComponentFieldsTNFO.OPTIMAL_STAFF));

        Optional<Entity> techOperCompWorkstationTime = normService.getTechOperCompWorkstationTime(operationComponent, workstation);

        OperationWorkTime partialOperationWorkTime;

        if (techOperCompWorkstationTime.isPresent()) {
            partialOperationWorkTime = operationWorkTimeService.estimateTechOperationWorkTimeForWorkstation(
                    operationComponent,
                    partialOperationComponentRuns,
                    includeTpz, true, techOperCompWorkstationTime.get(),
                    staffFactor);
        } else {
            partialOperationWorkTime = operationWorkTimeService.estimateOperationWorkTime(null, operationComponent,
                    partialOperationComponentRuns, includeTpz, true, false, staffFactor);
        }

        return partialOperationWorkTime.getMachineWorkTime();
    }

    private Date getFinishDateWithChangeovers(final Entity schedulePosition, final Date finishDate) {
        List<Entity> workstationChangeovers = schedulePosition.getHasManyField(SchedulePositionFields.CURRENT_WORKSTATION_CHANGEOVER_FOR_SCHEDULE_POSITIONS);

        if (!workstationChangeovers.isEmpty()) {
            Optional<Date> mayBeMaxFinishDate = workstationChangeoverService.getWorkstationChangeoversMaxFinishDate(workstationChangeovers);

            if (mayBeMaxFinishDate.isPresent()) {
                return mayBeMaxFinishDate.get();
            }
        }

        return finishDate;
    }

    private DataDefinition getOperationalTaskDD() {
        return dataDefinitionService
                .get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_OPERATIONAL_TASK);
    }

}
