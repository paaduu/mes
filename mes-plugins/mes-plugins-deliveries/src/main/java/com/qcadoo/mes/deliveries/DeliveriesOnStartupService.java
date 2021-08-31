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
package com.qcadoo.mes.deliveries;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.qcadoo.mes.basic.services.DashboardButtonService;
import com.qcadoo.mes.deliveries.columnExtension.DeliveriesColumnLoader;
import com.qcadoo.mes.deliveries.constants.DeliveriesConstants;
import com.qcadoo.plugin.api.Module;

@Component
public class DeliveriesOnStartupService extends Module {

    public static final String BASIC_DASHBOARD_BUTTON_IDENTIFIER_REQUIREMENTS_DELIVERIES_LIST = "basic.dashboardButton.identifier.requirements.deliveriesList";

    @Autowired
    private DeliveriesColumnLoader deliveriesColumnLoader;

    @Autowired
    private DashboardButtonService dashboardButtonService;

    @Transactional
    @Override
    public void multiTenantEnable() {
        deliveriesColumnLoader.addColumnsForDeliveries();
        deliveriesColumnLoader.addColumnsForOrders();
        dashboardButtonService.addButton(BASIC_DASHBOARD_BUTTON_IDENTIFIER_REQUIREMENTS_DELIVERIES_LIST,
                "/qcadooView/public/css/core/images/dashboard/deliveries.png", DeliveriesConstants.PLUGIN_IDENTIFIER,
                "deliveries");
    }

    @Transactional
    @Override
    public void multiTenantDisable() {
        deliveriesColumnLoader.deleteColumnsForDeliveries();
        deliveriesColumnLoader.deleteColumnsForOrders();
        dashboardButtonService.deleteButton(BASIC_DASHBOARD_BUTTON_IDENTIFIER_REQUIREMENTS_DELIVERIES_LIST);
    }

}
