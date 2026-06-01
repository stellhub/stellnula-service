package io.github.stellnula.repository;

public interface ConfigRevisionRepository {

  /** 查询全局 revision 表中的最新修订号。 */
  long findLatestRevision();
}
