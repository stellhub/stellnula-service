package io.github.stellnula.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcConfigRevisionRepository implements ConfigRevisionRepository {

  private static final String LATEST_REVISION_SQL =
      """
      select coalesce(max(revision), 0) as latest_revision
        from stn_config_revision
       where event_type in (
           'PUBLISHED',
           'DELETED',
           'ROLLED_BACK',
           'COPIED',
           'GRAY_PUBLISHED',
           'GRAY_RULE_CHANGED',
           'GRAY_ROLLED_BACK',
           'GRAY_FULL_RELEASE',
           'GRAY_ENDED',
           'CACHE_REBUILD'
       )
      """;

  private final JdbcTemplate jdbcTemplate;

  @Override
  public long findLatestRevision() {
    Long revision = jdbcTemplate.queryForObject(LATEST_REVISION_SQL, Long.class);
    return revision == null ? 0 : revision;
  }
}
