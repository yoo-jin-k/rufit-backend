package com.rufit.routine;

import com.rufit.workout.Workout;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class RoutineSearchTests {
    @Autowired RoutineRepository repository;
    @Autowired RoutineService service;
    @Autowired MockMvc mvc;
    @BeforeEach void clean() { repository.deleteAll(); }
    private Routine add(String owner,String title) {
        return repository.saveAndFlush(new Routine(UUID.randomUUID(),owner,title,List.of(new Workout.Entry("Squat",BigDecimal.TEN,5))));
    }
    @Test void paginationIsOwnerScopedStableAndIncludesTotalAndNextPage() {
        for(int i=0;i<23;i++) add("search-alice","Strength");
        add("search-bob","Strength");add("search-alice","Yoga");
        var first=service.search("search-alice"," strength ",0,20);
        var second=service.search("search-alice","STRENGTH",1,20);
        assertThat(first.totalElements()).isEqualTo(23);assertThat(first.hasNext()).isTrue();
        assertThat(first.items()).hasSize(20);assertThat(second.items()).hasSize(3);
        assertThat(second.hasNext()).isFalse();assertThat(second.totalElements()).isEqualTo(23);
        Set<UUID> ids=new HashSet<>();first.items().forEach(r -> ids.add(r.id()));
        second.items().forEach(r -> assertThat(ids.add(r.id())).isTrue());
        assertThat(ids).hasSize(23);
        assertThat(service.search("search-alice","",0,50).totalElements()).isEqualTo(24);
        assertThat(service.search("nobody","",0,20).items()).isEmpty();
        assertThat(service.search("search-alice","Strength",2,20).totalElements()).isEqualTo(23);
        var deleted=first.items().getFirst();service.delete(deleted.id(),"search-alice",deleted.version());
        assertThat(service.search("search-alice","Strength",0,20).totalElements()).isEqualTo(22);
    }
    @Test void wildcardCharactersAreLiteralAndApiRejectsInvalidRanges() throws Exception {
        add("search-alice","100%_! routine");add("search-alice","Normal routine");
        for(String query:List.of("%","_","!"))
            mvc.perform(get("/api/v1/routines/search").param("query",query).with(user("search-alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].title").value("100%_! routine"));
        mvc.perform(get("/api/v1/routines/search")).andExpect(status().isUnauthorized());
        for(String[] param:new String[][]{{"size","0"},{"size","51"},{"page","-1"},{"page","10001"},{"query","x".repeat(81)}})
            mvc.perform(get("/api/v1/routines/search").param(param[0],param[1]).with(user("search-alice")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/routines").with(user("search-alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }
}
