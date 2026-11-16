package com.project1.enrollment.queue;

public class QueueNotAllowedException extends RuntimeException {

    public QueueNotAllowedException(String userId) {
        super("User is not in active_users: " + userId);
    }
}
