package com.qcadoo.mes.orders.hooks;

import com.qcadoo.mes.orders.constants.SchedulePositionFields;
import com.qcadoo.mes.orders.services.WorkstationChangeoverService;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.Entity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;

@Service
public class SchedulePositionHooks {

    @Autowired
    private WorkstationChangeoverService workstationChangeoverService;

    public void onSave(final DataDefinition schedulePositionDD, final Entity schedulePosition) {
        setWorkstationChangeoverForSchedulePositions(schedulePosition);
    }

    private void setWorkstationChangeoverForSchedulePositions(final Entity schedulePosition) {
        Long schedulePositionId = schedulePosition.getId();
        Entity workstation = schedulePosition.getBelongsToField(SchedulePositionFields.WORKSTATION);
        Date startTime = schedulePosition.getDateField(SchedulePositionFields.START_TIME);

        if (Objects.nonNull(schedulePositionId)) {
            if (Objects.isNull(workstation) || Objects.isNull(startTime)) {
                deleteWorkstationChangeoverForSchedulePositions(schedulePosition);
            } else {
                if (checkIfSchedulePositionDataChanged(schedulePosition, workstation, startTime)) {
                    setCurrentWorkstationChangeoverForSchedulePositions(schedulePosition);
                }
            }
        }
    }

    private boolean checkIfSchedulePositionDataChanged(final Entity schedulePosition, final Entity workstation, final Date startTime) {
        Entity operationalTaskFromDB = schedulePosition.getDataDefinition().get(schedulePosition.getId());
        Entity workstationFromDB = operationalTaskFromDB.getBelongsToField(SchedulePositionFields.WORKSTATION);
        Date startTimeFromDB = operationalTaskFromDB.getDateField(SchedulePositionFields.START_TIME);

        boolean areWorkstationsSame = (Objects.isNull(workstation) ? Objects.isNull(workstationFromDB)
                : (Objects.nonNull(workstationFromDB) && workstation.getId().equals(workstationFromDB.getId())));
        boolean areStartTimesSame = (Objects.isNull(startTime) ? Objects.isNull(startTimeFromDB)
                : (Objects.nonNull(startTimeFromDB) && startTime.equals(startTimeFromDB)));

        return !areWorkstationsSame || !areStartTimesSame;
    }

    public void deleteWorkstationChangeoverForSchedulePositions(final Entity schedulePosition) {
        List<Entity> currentWorkstationChangeoverForOperationalTasks = schedulePosition.getHasManyField(SchedulePositionFields.CURRENT_WORKSTATION_CHANGEOVER_FOR_SCHEDULE_POSITIONS);

        currentWorkstationChangeoverForOperationalTasks.forEach(workstationChangeoverForSchedulePosition ->
                workstationChangeoverForSchedulePosition.getDataDefinition().delete(workstationChangeoverForSchedulePosition.getId()));
    }

    private void setCurrentWorkstationChangeoverForSchedulePositions(final Entity schedulePosition) {
        List<Entity> workstationChangeoverForSchedulePositions = workstationChangeoverService.findWorkstationChangeoversForSchedulePosition(schedulePosition);

        schedulePosition.setField(SchedulePositionFields.CURRENT_WORKSTATION_CHANGEOVER_FOR_SCHEDULE_POSITIONS, workstationChangeoverForSchedulePositions);
    }

}
