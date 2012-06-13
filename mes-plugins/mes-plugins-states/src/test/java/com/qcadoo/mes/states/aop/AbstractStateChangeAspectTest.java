package com.qcadoo.mes.states.aop;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Collections;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.google.common.collect.Lists;
import com.qcadoo.mes.states.MockStateChangeDescriber;
import com.qcadoo.mes.states.StateChangeEntityDescriber;
import com.qcadoo.mes.states.StateChangeTest;
import com.qcadoo.mes.states.annotation.RunInPhase;
import com.qcadoo.mes.states.constants.StateChangeStatus;
import com.qcadoo.mes.states.messages.constants.MessageType;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.EntityList;

public class AbstractStateChangeAspectTest extends StateChangeTest {

    private static final String STATE_FIELD_NAME = "state";

    private static final String STATE_FIELD_VALUE = "someState";

    @Aspect
    public static class TestStateChangeAspect extends AbstractStateListenerAspect {

        @RunInPhase(1)
        @org.aspectj.lang.annotation.Before("changeStateExecution(stateChange)")
        public void markEntityBefore(final Entity stateChange) {
            stateChange.setField("marked", true);
        }

        @Pointcut("this(TestStateChangeService)")
        protected void targetServicePointcut() {
        }

        @Override
        public StateChangeEntityDescriber getStateChangeEntityDescriber() {
            return new MockStateChangeDescriber();
        }

    }

    @Aspect
    public static class TestStateChangeService extends AbstractStateChangeAspect {

        @Override
        protected String getStateFieldName() {
            return STATE_FIELD_NAME;
        }

        @Override
        public void changeState(final Entity stateChangeEntity) {
            Entity targetEntity = stateChangeEntity.getBelongsToField(getChangeEntityDescriber().getOwnerFieldName());
            targetEntity.setField(getStateFieldName(),
                    stateChangeEntity.getField(getChangeEntityDescriber().getTargetStateFieldName()));
        }

        @Override
        public StateChangeEntityDescriber getChangeEntityDescriber() {
            return new MockStateChangeDescriber();
        }

        @Override
        protected void changeStatePhase(final Entity stateChangeEntity, final int phaseNumber) {
        }

    }

    @Aspect
    public static class AnotherStateChangeService extends AbstractStateChangeAspect {

        @Override
        protected String getStateFieldName() {
            return STATE_FIELD_NAME;
        }

        @Override
        public void changeState(final Entity stateChangeEntity) {
            Entity targetEntity = stateChangeEntity.getBelongsToField(getChangeEntityDescriber().getOwnerFieldName());
            targetEntity.setField(getStateFieldName(),
                    stateChangeEntity.getField(getChangeEntityDescriber().getTargetStateFieldName()));
        }

        @Override
        public StateChangeEntityDescriber getChangeEntityDescriber() {
            return new MockStateChangeDescriber();
        }

        @Override
        protected void changeStatePhase(final Entity stateChangeEntity, final int phaseNumber) {
        }

    }

    @Mock
    private Entity ownerEntity;

    @Mock
    private DataDefinition dataDefinition;

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);
        stubStateChangeEntity(DESCRIBER);
        EntityList emptyEntityList = mockEmptyEntityList();
        given(stateChangeEntity.getHasManyField("messages")).willReturn(emptyEntityList);
    }

    @Test
    public final void shouldFireListenersAndChangeState() {
        // given
        TestStateChangeService testStateChangeService = new TestStateChangeService();
        given(stateChangeEntity.getBelongsToField(DESCRIBER.getOwnerFieldName())).willReturn(ownerEntity);
        stubStringField(stateChangeEntity, DESCRIBER.getTargetStateFieldName(), STATE_FIELD_VALUE);
        stubEntityMock("", "");

        // when
        testStateChangeService.changeState(stateChangeEntity);

        // then
        verify(stateChangeEntity).setField("marked", true);
        verify(ownerEntity).setField(STATE_FIELD_NAME, STATE_FIELD_VALUE);
    }

    @Test
    public final void shouldNotFireListenersForOtherStateChangeService() {
        // given
        AnotherStateChangeService anotherStateChangeService = new AnotherStateChangeService();
        given(stateChangeEntity.getBelongsToField(DESCRIBER.getOwnerFieldName())).willReturn(ownerEntity);
        stubStringField(stateChangeEntity, DESCRIBER.getTargetStateFieldName(), STATE_FIELD_VALUE);
        stubEntityMock("", "");

        // when
        anotherStateChangeService.changeState(stateChangeEntity);

        // then
        verify(stateChangeEntity, never()).setField("marked", true);
        verify(ownerEntity).setField(STATE_FIELD_NAME, STATE_FIELD_VALUE);
    }

    @Test
    public final void shouldNotFireChangeStateIfStateChangeWasSuccessfulFinished() {
        // given
        AnotherStateChangeService anotherStateChangeService = new AnotherStateChangeService();
        given(stateChangeEntity.getBelongsToField(DESCRIBER.getOwnerFieldName())).willReturn(ownerEntity);
        given(stateChangeEntity.getStringField(DESCRIBER.getStatusFieldName())).willReturn(
                StateChangeStatus.SUCCESSFUL.getStringValue());
        mockStateChangeStatus(stateChangeEntity, StateChangeStatus.SUCCESSFUL);

        // when
        anotherStateChangeService.changeState(stateChangeEntity);

        // then
        verify(ownerEntity, never()).setField(Mockito.eq(STATE_FIELD_NAME), Mockito.any());
    }

    @Test
    public final void shouldNotFireChangeStateIfStateChangeWasFailureFinished() {
        // given
        AnotherStateChangeService anotherStateChangeService = new AnotherStateChangeService();
        given(stateChangeEntity.getBelongsToField(DESCRIBER.getOwnerFieldName())).willReturn(ownerEntity);
        given(stateChangeEntity.getStringField(DESCRIBER.getStatusFieldName())).willReturn(
                StateChangeStatus.SUCCESSFUL.getStringValue());
        mockStateChangeStatus(stateChangeEntity, StateChangeStatus.FAILURE);

        // when
        anotherStateChangeService.changeState(stateChangeEntity);

        // then
        verify(ownerEntity, never()).setField(Mockito.eq(STATE_FIELD_NAME), Mockito.any());
    }

    @Test
    public final void shouldNotFireChangeStateIfStateChangeWasPaused() {
        // given
        AnotherStateChangeService anotherStateChangeService = new AnotherStateChangeService();
        given(stateChangeEntity.getBelongsToField(DESCRIBER.getOwnerFieldName())).willReturn(ownerEntity);
        given(stateChangeEntity.getStringField(DESCRIBER.getStatusFieldName())).willReturn(
                StateChangeStatus.SUCCESSFUL.getStringValue());
        mockStateChangeStatus(stateChangeEntity, StateChangeStatus.PAUSED);

        // when
        anotherStateChangeService.changeState(stateChangeEntity);

        // then
        verify(ownerEntity, never()).setField(Mockito.eq(STATE_FIELD_NAME), Mockito.any());
    }

    @Test
    public final void shouldNotFireListenersIfStateChangeWasAlreadyFinished() {
        // given
        AnotherStateChangeService anotherStateChangeService = new AnotherStateChangeService();
        given(stateChangeEntity.getBelongsToField(DESCRIBER.getOwnerFieldName())).willReturn(ownerEntity);
        given(stateChangeEntity.getBooleanField(DESCRIBER.getStatusFieldName())).willReturn(true);
        given(stateChangeEntity.getField(DESCRIBER.getStatusFieldName())).willReturn(true);

        // when
        anotherStateChangeService.changeState(stateChangeEntity);

        // then
        verify(stateChangeEntity, never()).setField("marked", true);
    }

    @Test
    public final void shouldNotFireChangeStateIfStateChangeHaveErrorMessages() {
        // given
        AnotherStateChangeService anotherStateChangeService = new AnotherStateChangeService();
        EntityList messagesEntityList = mockEntityList(Lists.newArrayList(mockMessage(MessageType.FAILURE, "fail")));
        given(stateChangeEntity.getHasManyField(DESCRIBER.getMessagesFieldName())).willReturn(messagesEntityList);
        given(stateChangeEntity.getField(DESCRIBER.getMessagesFieldName())).willReturn(messagesEntityList);

        // when
        anotherStateChangeService.changeState(stateChangeEntity);

        // then
        verify(ownerEntity, never()).setField(Mockito.eq(STATE_FIELD_NAME), Mockito.any());
    }

    @Test
    public final void shouldNotFireListenersIfStateChangeHaveErrorMessages() {
        // given
        AnotherStateChangeService anotherStateChangeService = new AnotherStateChangeService();
        EntityList messagesEntityList = mockEntityList(Lists.newArrayList(mockMessage(MessageType.FAILURE, "fail")));
        given(stateChangeEntity.getHasManyField(DESCRIBER.getMessagesFieldName())).willReturn(messagesEntityList);
        given(stateChangeEntity.getField(DESCRIBER.getMessagesFieldName())).willReturn(messagesEntityList);

        // when
        anotherStateChangeService.changeState(stateChangeEntity);

        // then
        verify(stateChangeEntity, never()).setField("marked", true);
    }

    @SuppressWarnings("unchecked")
    private EntityList mockEmptyEntityList() {
        EntityList entityList = mock(EntityList.class);
        given(entityList.isEmpty()).willReturn(true);
        given(entityList.iterator()).willReturn(Collections.EMPTY_LIST.iterator());
        return entityList;
    }

    private void stubStringField(final Entity entity, final String fieldName, final String fieldValue) {
        given(entity.getStringField(fieldName)).willReturn(fieldValue);
        given(entity.getField(fieldName)).willReturn(fieldValue);
    }

    private void stubEntityMock(final String pluginIdentifier, final String modelName) {
        given(dataDefinition.getPluginIdentifier()).willReturn(pluginIdentifier);
        given(dataDefinition.getName()).willReturn(modelName);

    }

}