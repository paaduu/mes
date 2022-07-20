/**
 * ***************************************************************************
 * Copyright (c) 2010 Qcadoo Limited
 * Project: Qcadoo MES
 * Version: 1.4
 *
 * This file is part of Qcadoo.
 *
 * Qcadoo is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation; either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 * ***************************************************************************
 */
package com.qcadoo.mes.basic.hooks;

import com.qcadoo.mes.basic.constants.WorkstationFields;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.Entity;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class WorkstationModelHooks {

    public void onCreate(final DataDefinition workstationDD, final Entity workstation) {
        if (Objects.isNull(workstation.getField(WorkstationFields.BUFFER))) {
            workstation.setField(WorkstationFields.BUFFER, false);
        }
    }

    public boolean onDelete(final DataDefinition workstationDD, final Entity workstation) {
        boolean canDelete = workstation.getHasManyField(WorkstationFields.SUBASSEMBLIES).isEmpty();

        if (!canDelete) {
            workstation.addGlobalError("basic.workstation.delete.hasSubassemblies");
        }

        return canDelete;
    }

}
