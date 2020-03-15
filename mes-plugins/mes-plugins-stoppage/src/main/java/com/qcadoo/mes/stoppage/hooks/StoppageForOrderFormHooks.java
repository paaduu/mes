package com.qcadoo.mes.stoppage.hooks;

import com.qcadoo.mes.stoppage.constants.StoppageFields;
import com.qcadoo.model.api.Entity;
import com.qcadoo.view.api.ViewDefinitionState;
import com.qcadoo.view.api.components.FormComponent;
import com.qcadoo.view.api.components.LookupComponent;
import com.qcadoo.view.api.components.lookup.FilterValueHolder;

import java.util.Objects;

import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

@Service
public class StoppageForOrderFormHooks {

    private static final String L_ORDER = "order";

    private static final String L_PRODUCTION_TRACKING = "productionTracking";

    private static final String L_FORM = "form";

    private static final String L_CONTEXT_KEY_PRODUCTION_TRACKING = "window.mainTab.form.productionTracking";

    private static final String L_CONTEXT_KEY_ORDER = "window.mainTab.form.order";

    public final void onBeforeRender(final ViewDefinitionState view) throws JSONException {

        if (Objects.isNull(((FormComponent) view.getComponentByReference(L_FORM)).getEntityId())) {
            JSONObject context = view.getJsonContext();
            if (view.isViewAfterRedirect() && context.has(L_CONTEXT_KEY_ORDER)) {
                Long orderId = context.getLong(L_CONTEXT_KEY_ORDER);
                LookupComponent orderLookupComponent = (LookupComponent) view.getComponentByReference(L_ORDER);
                orderLookupComponent.setFieldValue(orderId);
                orderLookupComponent.setEnabled(false);
                orderLookupComponent.requestComponentUpdateState();
            }
        } else {
            LookupComponent orderLookupComponent = (LookupComponent) view.getComponentByReference(L_ORDER);
            LookupComponent productionTrackingComponent = (LookupComponent) view.getComponentByReference(L_PRODUCTION_TRACKING);
            Entity order = orderLookupComponent.getEntity();

            if (order != null) {
                FilterValueHolder holder = productionTrackingComponent.getFilterValue();

                holder.put(StoppageFields.ORDER, order.getId());

                productionTrackingComponent.setFilterValue(holder);
            }
        }
    }
}
