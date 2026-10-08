package io.github.stepprflow.monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import io.github.stepprflow.core.broker.MessageBroker;
import io.github.stepprflow.core.model.WorkflowMessage;
import io.github.stepprflow.core.model.WorkflowRegistrationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("MonitorAnnouncer")
class MonitorAnnouncerTest {

    @Mock
    private MessageBroker messageBroker;

    @InjectMocks
    private MonitorAnnouncer announcer;

    @Test
    @DisplayName("Publishes an ANNOUNCE message on the registration topic")
    void announcePublishesAnnounce() {
        announcer.announce();

        final ArgumentCaptor<WorkflowMessage> captor =
                ArgumentCaptor.forClass(WorkflowMessage.class);
        verify(messageBroker).send(
                eq(WorkflowRegistrationRequest.REGISTRATION_TOPIC), captor.capture());

        final WorkflowMessage sent = captor.getValue();
        assertThat(sent.getTopic())
                .isEqualTo(WorkflowRegistrationRequest.REGISTRATION_TOPIC);
        assertThat(sent.getMetadata().get(WorkflowRegistrationRequest.METADATA_ACTION))
                .isEqualTo(WorkflowRegistrationRequest.ACTION_ANNOUNCE);
    }

    @Test
    @DisplayName("Swallows broker failures so startup never crashes")
    void announceSwallowsFailures() {
        doThrow(new RuntimeException("broker down"))
                .when(messageBroker).send(any(), any());

        assertThatCode(() -> announcer.announce()).doesNotThrowAnyException();
    }
}
