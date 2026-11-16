package com.bugtracker.repository;

import com.bugtracker.entity.BugHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BugHistoryRepository extends JpaRepository<BugHistory, Long> {

    List<BugHistory> findByBugIdOrderByIdAsc(Long bugId);

    List<BugHistory> findTop10ByBugIdInOrderByIdDesc(Collection<Long> bugIds);
}
