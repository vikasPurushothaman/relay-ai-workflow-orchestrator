package com.relay.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MappingMetadataTest {
    @Test void everyColumnMatchesMigrationNullabilityAndType() throws Exception {
        String sql;
        try (var stream=getClass().getResourceAsStream("/db/migration/V1__relay_schema.sql")) {
            sql=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
        for (Class<?> type:new Class<?>[]{Workflow.class,Run.class,Step.class,StepAttempt.class,Approval.class,QueueJob.class}) {
            String table=type.getAnnotation(jakarta.persistence.Table.class).name();
            String definition=sql.split(Pattern.quote("CREATE TABLE "+table+" ("))[1].split("\\) ENGINE=")[0];
            var columns=new java.util.HashMap<String,Boolean>();
            var matcher=Pattern.compile("(?m)^  `([^`]+)` [A-Z]+(?:\\(\\d+\\))?( NOT NULL)?[,\\n]").matcher(definition);
            while (matcher.find()) columns.put(matcher.group(1),matcher.group(2)==null);
            var fields=new java.util.ArrayList<java.lang.reflect.Field>();
            for (var field:type.getDeclaredFields()) {
                if (field.isAnnotationPresent(EmbeddedId.class)) fields.addAll(java.util.List.of(field.getType().getDeclaredFields()));
                else fields.add(field);
            }
            for (var field:fields) {
                var column=field.getAnnotation(Column.class);
                if (column==null) continue;
                String name=column.name().replace("`","");
                assertThat(columns.remove(name)).as(table+"."+name+" nullability").isEqualTo(column.nullable());
            }
            assertThat(columns).as("unmapped "+table).isEmpty();
        }
        assertThat(Workflow.class.getDeclaredField("revision").isAnnotationPresent(Version.class)).isTrue();
        assertThat(Run.class.getDeclaredField("revision").isAnnotationPresent(Version.class)).isTrue();
    }
}
