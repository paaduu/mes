package com.qcadoo.mes.orders.services;

import com.google.common.collect.Lists;
import com.qcadoo.mes.basic.constants.AttributeDataType;
import com.qcadoo.mes.basic.constants.AttributeFields;
import com.qcadoo.mes.basic.constants.ProductAttributeValueFields;
import com.qcadoo.mes.basic.constants.ProductFields;
import com.qcadoo.mes.orders.constants.*;
import com.qcadoo.mes.orders.states.constants.OperationalTaskState;
import com.qcadoo.mes.orders.states.constants.OperationalTaskStateStringValues;
import com.qcadoo.mes.technologies.constants.WorkstationChangeoverNormChangeoverType;
import com.qcadoo.mes.technologies.constants.WorkstationChangeoverNormFields;
import com.qcadoo.mes.technologies.services.WorkstationChangeoverNormService;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.search.*;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class WorkstationChangeoverService {

    private static final String L_DOT = ".";

    private static final String L_ID = "id";

    private static final String L_COUNT = "count";

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private WorkstationChangeoverNormService workstationChangeoverNormService;

    public List<Entity> findWorkstationChangeoversForSchedulePosition(final Entity schedulePosition) {
        return findWorkstationChangeoversForSchedulePosition(schedulePosition, null);
    }

    public List<Entity> findWorkstationChangeoversForSchedulePosition(final Entity schedulePosition, final Entity previousSchedulePosition) {
        Date startTime = schedulePosition.getDateField(SchedulePositionFields.START_TIME);
        Entity workstation = schedulePosition.getBelongsToField(SchedulePositionFields.WORKSTATION);

        return findWorkstationChangeoversForSchedulePosition(startTime, workstation, schedulePosition, previousSchedulePosition);
    }

    public List<Entity> findWorkstationChangeoversForSchedulePosition(final Date startDate, final Entity workstation,
                                                                      final Entity schedulePosition,
                                                                      final Entity previousSchedulePosition) {
        List<Entity> workstationChangeovers = Lists.newArrayList();

        if (workstationChangeoverNormService.hasWorkstationChangeoverNorms(workstation)) {
            Entity previousOperationalTask = null;
            Entity previousProduct = null;

            if (Objects.nonNull(previousSchedulePosition)) {
                previousProduct = previousSchedulePosition.getBelongsToField(SchedulePositionFields.ORDER).getBelongsToField(OrderFields.PRODUCT);
            } else {
                previousOperationalTask = getPreviousOperationalTask(workstation, startDate);

                if (Objects.nonNull(previousOperationalTask)) {
                    previousProduct = previousOperationalTask.getBelongsToField(OperationalTaskFields.ORDER).getBelongsToField(OrderFields.PRODUCT);
                }
            }

            if (Objects.nonNull(previousProduct)) {
                Entity currentProduct = schedulePosition.getBelongsToField(SchedulePositionFields.ORDER).getBelongsToField(OrderFields.PRODUCT);

                List<Entity> currentProductAttributeValues = getProductAttributeValuesWithDataTypeCalculated(currentProduct);
                List<Entity> previousProductAttributeValues = getProductAttributeValuesWithDataTypeCalculated(previousProduct);

                for (Entity currentProductAttributeValue : currentProductAttributeValues) {
                    createChangeoversForAttribute(workstation, schedulePosition, previousSchedulePosition, workstationChangeovers,
                            previousOperationalTask, previousProductAttributeValues, currentProductAttributeValue);
                }
            }

            updateWorkstationChangeoversForSchedulePositionDates(workstationChangeovers, startDate);
        }

        return workstationChangeovers;
    }

    private void createChangeoversForAttribute(final Entity workstation,
                                               final Entity schedulePosition, final Entity previousSchedulePosition,
                                               final List<Entity> workstationChangeovers,
                                               final Entity previousOperationalTask,
                                               final List<Entity> previousProductAttributeValues,
                                               final Entity currentProductAttributeValue) {
        Entity attribute = currentProductAttributeValue.getBelongsToField(ProductAttributeValueFields.ATTRIBUTE);
        Entity attributeValue = currentProductAttributeValue.getBelongsToField(ProductAttributeValueFields.ATTRIBUTE_VALUE);

        List<Entity> filteredProductAttributeValues = previousProductAttributeValues.stream().filter(previousProductAttributeValue ->
                filterProductAttributeValuesWithAttribute(previousProductAttributeValue, attribute)).collect(Collectors.toList());

        if (!filteredProductAttributeValues.isEmpty()) {
            List<Entity> workstationChangeoverNorms = workstationChangeoverNormService.findWorkstationChangeoverNorms(workstation, attribute);

            for (Entity workstationChangeoverNorm : workstationChangeoverNorms) {
                String changeoverType = workstationChangeoverNorm.getStringField(WorkstationChangeoverNormFields.CHANGEOVER_TYPE);

                if (WorkstationChangeoverNormChangeoverType.BETWEEN_VALUES.getStringValue().equals(changeoverType)) {
                    Entity fromAttributeValue = workstationChangeoverNorm.getBelongsToField(WorkstationChangeoverNormFields.FROM_ATTRIBUTE_VALUE);
                    Entity toAttributeValue = workstationChangeoverNorm.getBelongsToField(WorkstationChangeoverNormFields.TO_ATTRIBUTE_VALUE);

                    if (attributeValue.getId().equals(toAttributeValue.getId()) && filteredProductAttributeValues.stream().anyMatch(previousProductAttributeValue ->
                            filterProductAttributeValuesWithAttributeValue(previousProductAttributeValue, fromAttributeValue))) {
                        Entity workstationChangeover = createWorkstationChangeoverForSchedulePosition(schedulePosition, previousSchedulePosition, workstationChangeoverNorm, previousOperationalTask);

                        workstationChangeovers.add(workstationChangeover);
                    }
                } else {
                    Entity workstationChangeover = createWorkstationChangeoverForSchedulePosition(schedulePosition, previousSchedulePosition, workstationChangeoverNorm, previousOperationalTask);

                    workstationChangeovers.add(workstationChangeover);
                }
            }
        }
    }

    private Entity createWorkstationChangeoverForSchedulePosition(final Entity currentSchedulePosition,
                                                                  final Entity previousSchedulePosition,
                                                                  final Entity workstationChangeoverNorm,
                                                                  final Entity previousOperationalTask) {
        Entity workstationChangeoverForSchedulePosition = getWorkstationChangeoverForSchedulePositionDD().create();

        workstationChangeoverForSchedulePosition.setField(WorkstationChangeoverForSchedulePositionFields.WORKSTATION_CHANGEOVER_NORM, workstationChangeoverNorm);
        workstationChangeoverForSchedulePosition.setField(WorkstationChangeoverForSchedulePositionFields.CURRENT_SCHEDULE_POSITION, currentSchedulePosition);
        workstationChangeoverForSchedulePosition.setField(WorkstationChangeoverForSchedulePositionFields.PREVIOUS_SCHEDULE_POSITION, previousSchedulePosition);
        workstationChangeoverForSchedulePosition.setField(WorkstationChangeoverForSchedulePositionFields.PREVIOUS_OPERATIONAL_TASK, previousOperationalTask);

        return workstationChangeoverForSchedulePosition;
    }

    private void updateWorkstationChangeoversForSchedulePositionDates(final List<Entity> workstationChangeovers,
                                                                      final Date startDate) {
        Date lastStartDate = startDate;

        for (Entity workstationChangeover : workstationChangeovers.stream().filter(e -> !e.getBelongsToField(WorkstationChangeoverForSchedulePositionFields.WORKSTATION_CHANGEOVER_NORM).getBooleanField(WorkstationChangeoverForOperationalTaskFields.IS_PARALLEL)).collect(Collectors.toList())) {
            Integer duration = workstationChangeover.getBelongsToField(WorkstationChangeoverForSchedulePositionFields.WORKSTATION_CHANGEOVER_NORM).getIntegerField(WorkstationChangeoverNormFields.DURATION);
            Date finishDate = new DateTime(lastStartDate).plusSeconds(duration).toDate();

            workstationChangeover.setField(WorkstationChangeoverForSchedulePositionFields.START_DATE, lastStartDate);
            workstationChangeover.setField(WorkstationChangeoverForSchedulePositionFields.FINISH_DATE, finishDate);

            lastStartDate = finishDate;
        }

        for (Entity workstationChangeover : workstationChangeovers.stream().filter(e -> e.getBelongsToField(WorkstationChangeoverForSchedulePositionFields.WORKSTATION_CHANGEOVER_NORM).getBooleanField(WorkstationChangeoverForOperationalTaskFields.IS_PARALLEL)).collect(Collectors.toList())) {
            Integer duration = workstationChangeover.getBelongsToField(WorkstationChangeoverForSchedulePositionFields.WORKSTATION_CHANGEOVER_NORM).getIntegerField(WorkstationChangeoverNormFields.DURATION);
            Date finishDate = new DateTime(startDate).plusSeconds(duration).toDate();

            workstationChangeover.setField(WorkstationChangeoverForSchedulePositionFields.START_DATE, startDate);
            workstationChangeover.setField(WorkstationChangeoverForSchedulePositionFields.FINISH_DATE, finishDate);
        }
    }

    public List<Entity> findWorkstationChangeoverForOperationalTasks(final Entity operationalTask) {
        List<Entity> workstationChangeoverForOperationalTasks = Lists.newArrayList();

        Optional<Entity> mayBePreviousOperationalTask = findPreviousOperationalTask(operationalTask);

        if (mayBePreviousOperationalTask.isPresent()) {
            Entity previousOperationalTask = mayBePreviousOperationalTask.get();

            workstationChangeoverForOperationalTasks = findWorkstationChangeoverForOperationalTasks(operationalTask, previousOperationalTask);
        }

        return workstationChangeoverForOperationalTasks;
    }

    public List<Entity> findWorkstationChangeoverForOperationalTasks(final Entity operationalTask,
                                                                     final Entity previousOperationalTask) {
        List<Entity> workstationChangeoverForOperationalTasks = Lists.newArrayList();

        Entity workstation = operationalTask.getBelongsToField(OperationalTaskFields.WORKSTATION);

        if (workstationChangeoverNormService.hasWorkstationChangeoverNorms(workstation)) {
            Date startDate = operationalTask.getDateField(OperationalTaskFields.START_DATE);

            Entity currentOperationalTaskOrder = operationalTask.getBelongsToField(OperationalTaskFields.ORDER);
            Entity currentOperationalTaskOrderProduct = currentOperationalTaskOrder.getBelongsToField(OrderFields.PRODUCT);

            Entity previousOperationalTaskOrder = previousOperationalTask.getBelongsToField(OperationalTaskFields.ORDER);
            Entity previousOperationalTaskOrderProduct = previousOperationalTaskOrder.getBelongsToField(OrderFields.PRODUCT);

            List<Entity> currentProductAttributeValues = getProductAttributeValuesWithDataTypeCalculated(currentOperationalTaskOrderProduct);
            List<Entity> previousProductAttributeValues = getProductAttributeValuesWithDataTypeCalculated(previousOperationalTaskOrderProduct);

            currentProductAttributeValues.forEach(currentProductAttributeValue -> {
                Entity attribute = currentProductAttributeValue.getBelongsToField(ProductAttributeValueFields.ATTRIBUTE);
                Entity attributeValue = currentProductAttributeValue.getBelongsToField(ProductAttributeValueFields.ATTRIBUTE_VALUE);

                List<Entity> filteredProductAttributeValues = previousProductAttributeValues.stream().filter(previousProductAttributeValue ->
                        filterProductAttributeValuesWithAttribute(previousProductAttributeValue, attribute)).collect(Collectors.toList());

                if (!filteredProductAttributeValues.isEmpty()) {
                    List<Entity> workstationChangeoverNorms = workstationChangeoverNormService.findWorkstationChangeoverNorms(workstation, attribute);

                    workstationChangeoverNorms.forEach(workstationChangeoverNorm -> {
                        String changeoverType = workstationChangeoverNorm.getStringField(WorkstationChangeoverNormFields.CHANGEOVER_TYPE);

                        if (WorkstationChangeoverNormChangeoverType.BETWEEN_VALUES.getStringValue().equals(changeoverType)) {
                            Entity fromAttributeValue = workstationChangeoverNorm.getBelongsToField(WorkstationChangeoverNormFields.FROM_ATTRIBUTE_VALUE);
                            Entity toAttributeValue = workstationChangeoverNorm.getBelongsToField(WorkstationChangeoverNormFields.TO_ATTRIBUTE_VALUE);

                            if (attributeValue.getId().equals(toAttributeValue.getId())) {
                                if (filteredProductAttributeValues.stream().anyMatch(previousProductAttributeValue ->
                                        filterProductAttributeValuesWithAttributeValue(previousProductAttributeValue, fromAttributeValue))) {
                                    Entity workstationChangeoverForOperationalTask = createWorkstationChangeoverForOperationalTask(operationalTask, previousOperationalTask, workstationChangeoverNorm, workstation, attribute);

                                    workstationChangeoverForOperationalTasks.add(workstationChangeoverForOperationalTask);
                                }
                            }
                        } else {
                            Entity workstationChangeoverForOperationalTask = createWorkstationChangeoverForOperationalTask(operationalTask, previousOperationalTask, workstationChangeoverNorm, workstation, attribute);

                            workstationChangeoverForOperationalTasks.add(workstationChangeoverForOperationalTask);
                        }
                    });
                }
            });

            updateWorkstationChangeoverForOperationalTasksDates(workstationChangeoverForOperationalTasks, startDate);
        }

        return workstationChangeoverForOperationalTasks;
    }

    private List<Entity> getProductAttributeValuesWithDataTypeCalculated(final Entity product) {
        return product.getHasManyField(ProductFields.PRODUCT_ATTRIBUTE_VALUES).stream().filter(this::filterProductAttributeValuesWithDataTypeCalculated).collect(Collectors.toList());
    }

    private boolean filterProductAttributeValuesWithDataTypeCalculated(final Entity productAttributeValue) {
        return AttributeDataType.CALCULATED.getStringValue().equals(productAttributeValue.getBelongsToField(ProductAttributeValueFields.ATTRIBUTE).getStringField(AttributeFields.DATA_TYPE));
    }

    private boolean filterProductAttributeValuesWithAttribute(final Entity productAttributeValue,
                                                              final Entity attribute) {
        return productAttributeValue.getBelongsToField(ProductAttributeValueFields.ATTRIBUTE).getId().equals(attribute.getId());
    }

    private boolean filterProductAttributeValuesWithAttributeValue(final Entity productAttributeValue,
                                                                   final Entity attributeValue) {
        return productAttributeValue.getBelongsToField(ProductAttributeValueFields.ATTRIBUTE_VALUE).getId().equals(attributeValue.getId());
    }

    private Entity createWorkstationChangeoverForOperationalTask(final Entity currentOperationalTask,
                                                                 final Entity previousOperationalTask,
                                                                 final Entity workstationChangeoverNorm,
                                                                 final Entity workstation, final Entity attribute) {
        Entity workstationChangeoverForOperationalTask = getWorkstationChangeoverForOperationalTaskDD().create();

        String name = workstationChangeoverNorm.getStringField(WorkstationChangeoverNormFields.NAME);
        String description = workstationChangeoverNorm.getStringField(WorkstationChangeoverNormFields.DESCRIPTION);
        Entity fromAttributeValue = workstationChangeoverNorm.getBelongsToField(WorkstationChangeoverNormFields.FROM_ATTRIBUTE_VALUE);
        Entity toAttributeValue = workstationChangeoverNorm.getBelongsToField(WorkstationChangeoverNormFields.TO_ATTRIBUTE_VALUE);
        Integer duration = workstationChangeoverNorm.getIntegerField(WorkstationChangeoverNormFields.DURATION);
        boolean isParallel = workstationChangeoverNorm.getBooleanField(WorkstationChangeoverNormFields.IS_PARALLEL);

        workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.NAME, name);
        workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.DESCRIPTION, description);
        workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.WORKSTATION, workstation);
        workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.ATTRIBUTE, attribute);
        workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.FROM_ATTRIBUTE_VALUE, fromAttributeValue);
        workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.TO_ATTRIBUTE_VALUE, toAttributeValue);
        workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.WORKSTATION_CHANGEOVER_NORM, workstationChangeoverNorm);
        workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.CURRENT_OPERATIONAL_TASK, currentOperationalTask);
        workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.PREVIOUS_OPERATIONAL_TASK, previousOperationalTask);
        workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.CHANGEOVER_TYPE, WorkstationChangeoverForOperationalTaskChangeoverType.BASED_ON_NORM.getStringValue());
        workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.DURATION, duration);
        workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.IS_PARALLEL, isParallel);

        return workstationChangeoverForOperationalTask;
    }

    private void updateWorkstationChangeoverForOperationalTasksDates(
            final List<Entity> workstationChangeoverForOperationalTasks, final Date startDate) {
        Date lastStartDate = startDate;

        for (Entity workstationChangeoverForOperationalTask : workstationChangeoverForOperationalTasks.stream().filter(this::isNotParallel).collect(Collectors.toList())) {
            Integer duration = workstationChangeoverForOperationalTask.getIntegerField(WorkstationChangeoverForOperationalTaskFields.DURATION);
            Date finishDate = new DateTime(lastStartDate).plusSeconds(duration).toDate();

            workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.START_DATE, lastStartDate);
            workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.FINISH_DATE, finishDate);

            lastStartDate = finishDate;
        }

        for (Entity workstationChangeoverForOperationalTask : workstationChangeoverForOperationalTasks.stream().filter(this::isParallel).collect(Collectors.toList())) {
            Integer duration = workstationChangeoverForOperationalTask.getIntegerField(WorkstationChangeoverForOperationalTaskFields.DURATION);
            Date finishDate = new DateTime(startDate).plusSeconds(duration).toDate();

            workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.START_DATE, startDate);
            workstationChangeoverForOperationalTask.setField(WorkstationChangeoverForOperationalTaskFields.FINISH_DATE, finishDate);
        }
    }

    private boolean isParallel(final Entity workstationChangeoverForOperationalTask) {
        return getWorkstationChangeoverForOperationalTaskIsParallel(workstationChangeoverForOperationalTask);
    }

    private boolean isNotParallel(final Entity workstationChangeoverForOperationalTask) {
        return !getWorkstationChangeoverForOperationalTaskIsParallel(workstationChangeoverForOperationalTask);
    }

    private boolean getWorkstationChangeoverForOperationalTaskIsParallel(
            final Entity workstationChangeoverForOperationalTask) {
        return workstationChangeoverForOperationalTask.getBooleanField(WorkstationChangeoverForOperationalTaskFields.IS_PARALLEL);
    }

    public Optional<Date> getWorkstationChangeoversMaxFinishDate(final List<Entity> workstationChangeovers) {
        return workstationChangeovers.stream().map(workstationChangeover ->
                        workstationChangeover.getDateField(WorkstationChangeoverForOperationalTaskFields.FINISH_DATE))
                .max(Date::compareTo);
    }

    public Optional<Entity> findPreviousOperationalTask(final Entity operationalTask) {
        if (Objects.nonNull(operationalTask)) {
            Entity workstation = operationalTask.getBelongsToField(OperationalTaskFields.WORKSTATION);
            Date startDate = operationalTask.getDateField(OperationalTaskFields.START_DATE);

            SearchCriteriaBuilder searchCriteriaBuilder = getOperationalTaskDD().find();

            addWorkstationAndFinishDateSearchRestrictions(searchCriteriaBuilder, workstation, startDate);
            addIdSearchRestrictions(searchCriteriaBuilder, operationalTask.getId());

            return Optional.ofNullable(searchCriteriaBuilder
                    .addOrder(SearchOrders.desc(OperationalTaskFields.FINISH_DATE))
                    .setMaxResults(1).uniqueResult());
        } else {
            return Optional.empty();
        }
    }

    public Optional<Entity> findPreviousOperationalTask(final Entity operationalTask,
                                                        final Entity skipOperationalTask) {
        if (Objects.nonNull(operationalTask)) {
            Entity workstation = operationalTask.getBelongsToField(OperationalTaskFields.WORKSTATION);

            Date startDate = operationalTask.getDateField(OperationalTaskFields.START_DATE);

            SearchCriteriaBuilder searchCriteriaBuilder = getOperationalTaskDD().find();

            addWorkstationAndFinishDateSearchRestrictions(searchCriteriaBuilder, workstation, startDate);
            addIdSearchRestrictions(searchCriteriaBuilder, skipOperationalTask.getId());

            return Optional.ofNullable(searchCriteriaBuilder
                    .addOrder(SearchOrders.desc(OperationalTaskFields.FINISH_DATE))
                    .setMaxResults(1).uniqueResult());
        } else {
            return Optional.empty();
        }
    }

    public List<Entity> getPreviousOperationalTasks(final Entity operationalTask,
                                                    final Entity skipOperationalTask) {
        Entity workstation = operationalTask.getBelongsToField(OperationalTaskFields.WORKSTATION);
        Date startDate = operationalTask.getDateField(OperationalTaskFields.START_DATE);

        SearchCriteriaBuilder searchCriteriaBuilder = getOperationalTaskDD().find();

        addWorkstationAndFinishDateSearchRestrictions(searchCriteriaBuilder, workstation, startDate);
        addIdSearchRestrictions(searchCriteriaBuilder, skipOperationalTask.getId());

        return searchCriteriaBuilder
                .addOrder(SearchOrders.desc(OperationalTaskFields.FINISH_DATE))
                .list().getEntities();
    }

    private Entity getPreviousOperationalTask(final Entity workstation, final Date operationalTaskStartDate) {
        SearchCriteriaBuilder searchCriteriaBuilder = getOperationalTaskDD().find();

        addWorkstationAndFinishDateSearchRestrictions(searchCriteriaBuilder, workstation, operationalTaskStartDate);

        return searchCriteriaBuilder.addOrder(SearchOrders.desc(OperationalTaskFields.FINISH_DATE))
                .setMaxResults(1).uniqueResult();
    }

    public Optional<Entity> findNextOperationalTask(final Entity operationalTask) {
        if (Objects.nonNull(operationalTask)) {
            Entity workstation = operationalTask.getBelongsToField(OperationalTaskFields.WORKSTATION);

            if (Objects.nonNull(workstation)) {
                Date finishDate = operationalTask.getDateField(OperationalTaskFields.FINISH_DATE);

                SearchCriteriaBuilder searchCriteriaBuilder = getOperationalTaskDD().find();

                addWorkstationAndStartDateSearchRestrictions(searchCriteriaBuilder, workstation, finishDate);
                addIdSearchRestrictions(searchCriteriaBuilder, operationalTask.getId());

                return Optional.ofNullable(searchCriteriaBuilder
                        .addOrder(SearchOrders.asc(OperationalTaskFields.START_DATE))
                        .setMaxResults(1).uniqueResult());
            } else {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }
    }

    private void addWorkstationAndFinishDateSearchRestrictions(final SearchCriteriaBuilder searchCriteriaBuilder,
                                                               final Entity workstation, final Date startDate) {
        addWorkstationSearchRestrictions(searchCriteriaBuilder, OperationalTaskFields.WORKSTATION, workstation);
        addStateSearchRestrictions(searchCriteriaBuilder, OperationalTaskFields.STATE, OperationalTaskState.REJECTED.getStringValue());
        addDateLeSearchRestrictions(searchCriteriaBuilder, OperationalTaskFields.FINISH_DATE, startDate);
    }

    private void addWorkstationAndStartDateSearchRestrictions(final SearchCriteriaBuilder searchCriteriaBuilder,
                                                              final Entity workstation, final Date finishDate) {
        addWorkstationSearchRestrictions(searchCriteriaBuilder, OperationalTaskFields.WORKSTATION, workstation);
        addStateSearchRestrictions(searchCriteriaBuilder, OperationalTaskFields.STATE, OperationalTaskState.REJECTED.getStringValue());
        addDateGeSearchRestrictions(searchCriteriaBuilder, OperationalTaskFields.START_DATE, finishDate);
    }

    private void addWorkstationSearchRestrictions(final SearchCriteriaBuilder searchCriteriaBuilder,
                                                  final String workstationFieldName, final Entity workstation) {
        searchCriteriaBuilder.createAlias(workstationFieldName, workstationFieldName, JoinType.LEFT);
        searchCriteriaBuilder.add(SearchRestrictions.eq(workstationFieldName + L_DOT + L_ID, workstation.getId()));
    }

    private void addDateLeSearchRestrictions(final SearchCriteriaBuilder searchCriteriaBuilder,
                                           final String dateFieldName, final Date date) {
        searchCriteriaBuilder.add(SearchRestrictions.le(dateFieldName, date));
    }

    private void addDateGeSearchRestrictions(final SearchCriteriaBuilder searchCriteriaBuilder,
                                             final String dateFieldName, final Date date) {
        searchCriteriaBuilder.add(SearchRestrictions.ge(dateFieldName, date));
    }

    private void addStateSearchRestrictions(final SearchCriteriaBuilder searchCriteriaBuilder,
                                            final String stateFieldName, final String state) {
        searchCriteriaBuilder.add(SearchRestrictions.ne(stateFieldName, state));
    }

    private static void addIdSearchRestrictions(final SearchCriteriaBuilder searchCriteriaBuilder, final Long id) {
        if (Objects.nonNull(id)) {
            searchCriteriaBuilder.add(SearchRestrictions.idNe(id));
        }
    }

    public boolean hasWorkstationChangeoverForOperationalTasks(final Entity workstationChangeoverNorm) {
        Entity workstationChangeoverForOperationalTask = getWorkstationChangeoverForOperationalTaskDD().find()
                .createAlias(WorkstationChangeoverForOperationalTaskFields.WORKSTATION_CHANGEOVER_NORM, WorkstationChangeoverForOperationalTaskFields.WORKSTATION_CHANGEOVER_NORM, JoinType.LEFT)
                .add(SearchRestrictions.eq(WorkstationChangeoverForOperationalTaskFields.WORKSTATION_CHANGEOVER_NORM + L_DOT + L_ID, workstationChangeoverNorm.getId()))
                .setProjection(SearchProjections.alias(SearchProjections.countDistinct(L_ID), L_COUNT))
                .addOrder(SearchOrders.desc(L_COUNT)).setMaxResults(1).uniqueResult();

        Long countValue = (Long) workstationChangeoverForOperationalTask.getField(L_COUNT);

        return countValue > 0;
    }

    public Optional<Entity> getOperationalTask(final String number) {
        return Optional.ofNullable(getOperationalTaskDD().find().add(SearchRestrictions.eq(OperationalTaskFields.NUMBER, number))
                .add(SearchRestrictions.not(SearchRestrictions.in(OperationalTaskFields.STATE, Lists.newArrayList(OperationalTaskStateStringValues.FINISHED, OperationalTaskStateStringValues.REJECTED))))
                .setMaxResults(1).uniqueResult());
    }

    private DataDefinition getOperationalTaskDD() {
        return dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_OPERATIONAL_TASK);
    }

    public DataDefinition getWorkstationChangeoverForOperationalTaskDD() {
        return dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_WORKSTATION_CHANGEOVER_FOR_OPERATIONAL_TASK);
    }

    private DataDefinition getWorkstationChangeoverForSchedulePositionDD() {
        return dataDefinitionService.get(OrdersConstants.PLUGIN_IDENTIFIER, OrdersConstants.MODEL_WORKSTATION_CHANGEOVER_FOR_SCHEDULE_POSITION);
    }

}
