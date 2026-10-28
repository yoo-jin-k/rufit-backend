package com.rufit.exercise;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ExpandedExerciseCatalogTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ExerciseService service;
    @Autowired DataSource dataSource;
    JsonNode catalog() throws Exception {try(var stream=getClass().getResourceAsStream("/catalog/exercises-v2.json")){return mapper.readTree(stream).get("exercises");}}
    @Test void all110CanonicalRowsAndSearchKeysMatchPublishedSource() throws Exception {
        var catalog=catalog();assertThat(catalog.size()).isEqualTo(110);
        assertThat(jdbc.queryForObject("select count(*) from exercises where owner_name is null",Integer.class)).isEqualTo(110);
        Set<String> keys=new HashSet<>();
        for(var entry:catalog) {
            UUID id=UUID.fromString(entry.get("id").asString());var actual=service.get(id,"catalog-v2-check");
            assertThat(actual.name()).isEqualTo(entry.get("name").asString());assertThat(actual.bodyPart().name()).isEqualTo(entry.get("bodyPart").asString());
            assertThat(actual.equipment().name()).isEqualTo(entry.get("equipment").asString());assertThat(actual.custom()).isFalse();
            assertThat(actual.archivedAt()).isNull();assertThat(actual.version()).isZero();
            assertThat(actual.recommendedRestSeconds()).isEqualTo(entry.get("recommendedRestSeconds").isNull()?null:entry.get("recommendedRestSeconds").asInt());
            Set<String> aliases=new HashSet<>();aliases.add(ExerciseSearchText.normalize(entry.get("name").asString()));
            for(var alias:entry.get("aliases")) aliases.add(ExerciseSearchText.normalize(alias.asString()));
            assertThat(jdbc.queryForList("select normalized_alias from exercise_search_aliases where exercise_id=?",String.class,id)).containsExactlyInAnyOrderElementsOf(aliases);
            for(String alias:aliases) assertThat(keys.add(alias)).as("다른 운동 간 정확한 검색어 충돌").isTrue();
            mvc.perform(get("/api/v1/exercises/"+id).with(user("catalog-v2-check"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.name").value(actual.name()));
        }
    }
    @Test void everyPublishedAliasFindsItsExerciseWithoutDuplicatingPageRows() throws Exception {
        for(var entry:catalog()) for(var alias:entry.get("aliases")) {
            var result=service.search("catalog-v2-alias",alias.asString(),false,null,null,0,100);
            assertThat(result.items()).extracting(ExerciseController.View::id).contains(UUID.fromString(entry.get("id").asString())).doesNotHaveDuplicates();
        }
        var result=service.search("catalog-v2-alias","ＤＵＭＢＢＥＬＬ＿ＦＬＹ",false,Exercise.BodyPart.CHEST,Exercise.Equipment.DUMBBELL,0,20);
        assertThat(result.items()).extracting(ExerciseController.View::name).containsExactly("덤벨 플라이");
        assertThat(service.search("catalog-v2-alias","케이블 크로스오버",false,Exercise.BodyPart.CHEST,Exercise.Equipment.CABLE,0,20).items())
            .extracting(ExerciseController.View::name).containsExactly("케이블 체스트 플라이");
        assertThat(service.search("catalog-v2-alias","사레레",false,Exercise.BodyPart.LEGS,null,0,20).items()).isEmpty();
    }
    @Test void allPagesAndEachBodyEquipmentFilterReturnDistinctCanonicalItems() throws Exception {
        Set<UUID> expected=new HashSet<>();for(var e:catalog()) expected.add(UUID.fromString(e.get("id").asString()));
        List<UUID> received=new ArrayList<>();
        for(int page=0;;page++) {
            var result=service.search("catalog-v2-pages","",false,null,null,page,37);assertThat(result.totalElements()).isEqualTo(110);
            received.addAll(result.items().stream().map(ExerciseController.View::id).toList());if(!result.hasNext()) break;
        }
        assertThat(received).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        for(var part:Exercise.BodyPart.values()) for(var equipment:Exercise.Equipment.values()) {
            List<UUID> matching=new ArrayList<>();
            for(var e:catalog()) if(e.get("bodyPart").asString().equals(part.name()) && e.get("equipment").asString().equals(equipment.name())) matching.add(UUID.fromString(e.get("id").asString()));
            var result=service.search("catalog-v2-pages","",false,part,equipment,0,100);
            assertThat(result.items()).extracting(ExerciseController.View::id).containsExactlyInAnyOrderElementsOf(matching);
            assertThat(result.totalElements()).isEqualTo(matching.size());
        }
    }
    @Test void newExerciseUsesExistingRestFallbackAndDoesNotExposeOtherOwnersCustomRows() throws Exception {
        UUID id=UUID.fromString(catalog().get(18).get("id").asString());String owner="catalog-v2-rest";
        jdbc.update("insert into rest_preferences(owner_name,default_rest_seconds,version) values(?,47,1) on conflict(owner_name) do update set default_rest_seconds=47",owner);
        try {
            mvc.perform(get("/api/v1/exercises/"+id+"/rest").with(user(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedRestSeconds").isEmpty()).andExpect(jsonPath("$.effectiveRestSeconds").value(47)).andExpect(jsonPath("$.source").value("DEFAULT"));
            var custom=service.create("catalog-secret-owner",UUID.randomUUID(),new ExerciseController.Create("비공개 검색용",Exercise.BodyPart.CHEST,Exercise.Equipment.OTHER));
            assertThat(service.search(owner,"비공개 검색용",false,null,null,0,20).items()).isEmpty();
            assertThat(service.search("catalog-secret-owner","비공개 검색용",false,null,null,0,20).items()).extracting(ExerciseController.View::id).containsExactly(custom.id());
            jdbc.update("delete from exercises where id=?",custom.id());
        } finally {jdbc.update("delete from rest_preferences where owner_name=?",owner);}
    }
    @Test void upgradeFromV25PreservesExistingExerciseWorkoutRoutineAndPersonalRestRows() {
        // 같은 시험 DB 안의 새 임시 스키마만 사용한다. public과 FE preview를 초기화하지 않는다.
        String schema="catalog_upgrade_"+UUID.randomUUID().toString().replace("-","");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("25").load().migrate();
            UUID exercise=UUID.fromString("00000000-0000-4000-8000-000000000001"),workout=UUID.randomUUID(),routine=UUID.randomUUID();
            jdbc.update("insert into "+schema+".workouts(id,owner_name,started_at,version,note) values(?,'upgrade-owner',current_timestamp,7,'기존 메모')",workout);
            jdbc.update("insert into "+schema+".workout_entries(workout_id,entry_order,exercise_name,exercise_id,weight,reps) values(?,0,'과거 운동명 스냅샷',?,1,2)",workout,exercise);
            jdbc.update("insert into "+schema+".routines(id,owner_name,title,version) values(?,'upgrade-owner','기존 루틴',3)",routine);
            jdbc.update("insert into "+schema+".routine_entries(routine_id,entry_order,exercise_name,exercise_id,weight,reps) values(?,0,'기존 루틴 스냅샷',?,4,5)",routine,exercise);
            jdbc.update("insert into "+schema+".exercise_rest_preferences(owner_name,exercise_id,rest_seconds,version) values('upgrade-owner',?,0,2)",exercise);
            var tables=List.of("exercises","workouts","workout_entries","routines","routine_entries","exercise_rest_preferences");
            Map<String,List<String>> before=new HashMap<>();
            for(String table:tables) before.put(table,jdbc.queryForList("select row_to_json(t)::text from "+schema+"."+table+" t order by row_to_json(t)::text",String.class));
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForObject("select count(*) from "+schema+".exercises",Integer.class)).isEqualTo(110);
            for(String table:tables) {
                String where=table.equals("exercises")?" where id::text like '00000000-0000-4000-8000-%'":"";
                assertThat(jdbc.queryForList("select row_to_json(t)::text from "+schema+"."+table+" t"+where+" order by row_to_json(t)::text",String.class)).isEqualTo(before.get(table));
            }
        } finally {jdbc.execute("drop schema if exists "+schema+" cascade");}
    }
    @Test void newCanonicalExerciseCanBeRecordedAndReadBackWithoutTrustingClientAlias() throws Exception {
        var exercise=catalog().get(18);String owner="catalog-record-"+UUID.randomUUID();
        var response=mvc.perform(post("/api/v1/workouts").with(user(owner)).with(csrf())).andExpect(status().isCreated()).andReturn().getResponse();
        String id=mapper.readTree(response.getContentAsString()).get("id").asString();
        try {
            var request=Map.of("version",0,"set",Map.of("exerciseId",exercise.get("id").asString(),"exerciseName","임의 별칭","weight",1,"reps",2));
            mvc.perform(post("/api/v1/workouts/"+id+"/sets").with(user(owner)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sets[0].exerciseName").value(exercise.get("name").asString()));
            mvc.perform(get("/api/v1/workouts/"+id).with(user(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.sets[0].exerciseId").value(exercise.get("id").asString()))
                .andExpect(jsonPath("$.sets[0].weight").value(1)).andExpect(jsonPath("$.sets[0].reps").value(2));
            mvc.perform(get("/api/v1/workouts/"+id).with(user("other-catalog-owner"))).andExpect(status().isNotFound());
        } finally {jdbc.update("delete from workouts where id=?",UUID.fromString(id));}
    }
}
