package com.asmipahwa.leaderboard.debug;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin switch for {@link FaultInjector}. Not registered unless explicitly enabled. */
@RestController
@RequestMapping("/admin/faults")
@ConditionalOnProperty(name = "leaderboard.admin.enabled", havingValue = "true")
public class FaultController {

  private final FaultInjector faults;

  public FaultController(FaultInjector faults) {
    this.faults = faults;
  }

  @PutMapping("/slow-rank-query")
  public Map<String, Long> slowRankQuery(@RequestParam long ms) {
    faults.setSlowRankQueryMillis(ms);
    return Map.of("slowRankQueryMillis", faults.slowRankQueryMillis());
  }

  @GetMapping
  public Map<String, Long> current() {
    return Map.of("slowRankQueryMillis", faults.slowRankQueryMillis());
  }
}
