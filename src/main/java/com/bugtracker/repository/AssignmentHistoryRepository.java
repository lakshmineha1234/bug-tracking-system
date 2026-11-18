package com.bugtracker.repository;

import com.bugtracker.entity.AssignmentHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AssignmentHistoryRepository extends JpaRepository<AssignmentHistory, Long> {

    List<AssignmentHistory> findByBugIdOrderByIdAsc(Long bugId);

    List<AssignmentHistory> findTop10ByBugIdInOrderByIdDesc(Collection<Long> bugIds);
}
