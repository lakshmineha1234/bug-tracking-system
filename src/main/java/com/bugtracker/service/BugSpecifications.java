package com.bugtracker.service;

import com.bugtracker.dto.BugFilter;
import com.bugtracker.entity.Bug;
import com.bugtracker.entity.BugStatus;
import com.bugtracker.entity.Priority;
import com.bugtracker.entity.Project;
import com.bugtracker.entity.Severity;
import com.bugtracker.entity.User;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Database queries for listing bugs. Mirrors BugPermissions.canView so lists only contain accessible bugs. */
public final class BugSpecifications {

    private BugSpecifications() {
    }

    public static Specification<Bug> visibleTo(User user) {
        return switch (user.getRole()) {
            case ADMIN -> (root, query, cb) -> cb.conjunction();
            case DEVELOPER -> (root, query, cb) -> cb.equal(root.get("assignedTo").get("id"), user.getId());
            case TESTER -> (root, query, cb) -> {
                Subquery<Long> memberProjects = query.subquery(Long.class);
                Root<Project> project = memberProjects.from(Project.class);
                Join<Project, User> member = project.join("members");
                memberProjects.select(project.get("id")).where(cb.equal(member.get("id"), user.getId()));
                return root.get("project").get("id").in(memberProjects);
            };
        };
    }

    /** Columns the bug list can be sorted by. */
    public static final Set<String> SORT_KEYS =
            Set.of("id", "title", "project", "status", "priority", "severity", "assignee", "updatedAt");

    /**
     * Orders the results. Status, priority and severity sort by importance (OPEN first, CRITICAL first),
     * not alphabetically. Ties are broken by newest bug first, so pages are stable.
     */
    public static Specification<Bug> orderBy(String key, boolean ascending) {
        return (root, query, cb) -> {
            Class<?> resultType = query.getResultType();
            if (resultType == Long.class || resultType == long.class) {
                return null; // the count query used for paging needs no ordering
            }
            Expression<?> sortBy = switch (key) {
                case "id" -> root.get("id");
                case "title" -> cb.lower(root.get("title"));
                case "project" -> cb.lower(root.join("project", JoinType.LEFT).get("projectName"));
                case "assignee" -> cb.lower(root.join("assignedTo", JoinType.LEFT).get("name"));
                case "updatedAt" -> root.get("updatedAt");
                case "status" -> rank(cb, root.get("status"),
                        List.of(BugStatus.OPEN, BugStatus.IN_PROGRESS, BugStatus.FIXED, BugStatus.CLOSED));
                case "priority" -> rank(cb, root.get("priority"),
                        List.of(Priority.CRITICAL, Priority.HIGH, Priority.MEDIUM, Priority.LOW));
                case "severity" -> rank(cb, root.get("severity"),
                        List.of(Severity.CRITICAL, Severity.MAJOR, Severity.MINOR));
                default -> throw new IllegalArgumentException("Unknown sort key " + key);
            };
            List<Order> orders = new ArrayList<>();
            orders.add(ascending ? cb.asc(sortBy) : cb.desc(sortBy));
            if (!key.equals("id")) {
                orders.add(cb.desc(root.get("id")));
            }
            query.orderBy(orders);
            return null;
        };
    }

    private static <E extends Enum<E>> Expression<Integer> rank(CriteriaBuilder cb, Path<E> path, List<E> order) {
        CriteriaBuilder.SimpleCase<E, Integer> rank = cb.selectCase(path);
        for (int i = 0; i < order.size(); i++) {
            rank = rank.when(order.get(i), i);
        }
        return rank.otherwise(order.size());
    }

    /**
     * Escape character for LIKE patterns, so % and _ typed in a search match themselves. Not a backslash,
     * because MySQL treats backslashes in SQL string literals as escapes of their own.
     */
    static final char LIKE_ESCAPE = '!';

    static String escapeLike(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public static Specification<Bug> matches(BugFilter f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (f.status() != null) {
                predicates.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.priority() != null) {
                predicates.add(cb.equal(root.get("priority"), f.priority()));
            }
            if (f.severity() != null) {
                predicates.add(cb.equal(root.get("severity"), f.severity()));
            }
            if (f.projectId() != null) {
                predicates.add(cb.equal(root.get("project").get("id"), f.projectId()));
            }
            if (Boolean.TRUE.equals(f.unassigned())) {
                predicates.add(cb.isNull(root.get("assignedTo")));
            } else if (f.assigneeId() != null) {
                predicates.add(cb.equal(root.get("assignedTo").get("id"), f.assigneeId()));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String keyword = f.q().trim().toLowerCase(Locale.ROOT);
                String like = "%" + escapeLike(keyword) + "%";
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("title")), like, LIKE_ESCAPE));
                any.add(cb.like(cb.lower(root.get("description")), like, LIKE_ESCAPE));
                String digits = keyword.startsWith("#") ? keyword.substring(1) : keyword;
                if (digits.matches("\\d{1,18}")) {
                    any.add(cb.equal(root.get("id"), Long.parseLong(digits)));
                }
                predicates.add(cb.or(any.toArray(new Predicate[0])));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
