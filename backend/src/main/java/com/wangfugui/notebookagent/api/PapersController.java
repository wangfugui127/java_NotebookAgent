package com.wangfugui.notebookagent.api;

import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.core.ApiException;
import com.wangfugui.notebookagent.core.CurrentUser;
import com.wangfugui.notebookagent.domain.User;
import com.wangfugui.notebookagent.providers.OpenAlexProvider;
import com.wangfugui.notebookagent.repository.NotebookRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notebooks/{notebookId}/papers")
public class PapersController {

    private final NotebookRepository notebooks;
    private final AppProperties props;

    public PapersController(NotebookRepository notebooks, AppProperties props) {
        this.notebooks = notebooks;
        this.props = props;
    }

    private void owned(String notebookId, String userId) {
        notebooks.findByIdAndOwnerId(notebookId, userId)
                .orElseThrow(() -> ApiException.notFound("notebook not found"));
    }

    @GetMapping("/search")
    public Map<String, Object> search(@PathVariable String notebookId,
                                      @RequestParam("q") String q,
                                      @RequestParam(value = "year_from", required = false) Integer yearFrom,
                                      @RequestParam(value = "year_to", required = false) Integer yearTo,
                                      @RequestParam(value = "limit", defaultValue = "20") int limit,
                                      @CurrentUser User user) {
        owned(notebookId, user.getId());
        if (q.length() < 2 || q.length() > 500) {
            throw ApiException.unprocessable("q must be between 2 and 500 characters");
        }
        if (yearFrom != null && yearTo != null && yearFrom > yearTo) {
            throw ApiException.unprocessable("year_from must not exceed year_to");
        }
        int bounded = Math.min(Math.max(limit, 1), 50);
        List<Map<String, Object>> items = new OpenAlexProvider(props).search(q, yearFrom, yearTo, bounded);
        return Map.of("items", items);
    }

    @GetMapping("/{paperId}")
    public Map<String, Object> details(@PathVariable String notebookId, @PathVariable String paperId,
                                       @CurrentUser User user) {
        owned(notebookId, user.getId());
        return Map.of("item", new OpenAlexProvider(props).get(paperId));
    }
}
