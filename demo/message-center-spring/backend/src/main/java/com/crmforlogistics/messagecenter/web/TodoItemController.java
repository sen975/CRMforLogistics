package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/todos")
public class TodoItemController {
    private final TodoItemService service;
    public TodoItemController(TodoItemService service) { this.service = service; }
    @GetMapping public List<TodoResponse> list() { return service.list(SecurityUtil.currentUserId()).stream().map(TodoResponse::from).toList(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public TodoResponse create(@RequestBody TodoRequest request) { return TodoResponse.from(service.create(SecurityUtil.currentUserId(), request.date(), request.title(), request.time(), request.note())); }
    @PatchMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void complete(@PathVariable UUID id, @RequestBody CompletionRequest request) { service.setCompleted(SecurityUtil.currentUserId(), id, request.completed()); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id) { service.delete(SecurityUtil.currentUserId(), id); }
    public record TodoRequest(String date, String title, String time, String note) {}
    public record CompletionRequest(boolean completed) {}
    public record TodoResponse(UUID id, String date, String title, String time, String note, boolean completed, Instant createdAt) {
        static TodoResponse from(TodoItemEntity item) { return new TodoResponse(item.getId(), item.getDueDate().toString(), item.getTitle(), item.getDueTime() == null ? null : item.getDueTime().toString(), item.getNote(), item.isCompleted(), item.getCreatedAt()); }
    }
}
