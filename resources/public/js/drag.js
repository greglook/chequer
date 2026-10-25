// Drag tasks by their grip to reorder them, within a list of tasks or into another one.
//
// Lists of tasks are [data-task-list] elements whose data-group is the group they hold
// ('' for none), and tasks are their [data-task-id] children. While a task is dragged it
// moves through the page's DOM, so the others make room for it; on release the task gets a
// task-drop event whose detail is {group, before}: the group it's now in and the id of the
// task it now precedes ('' when last). The task's data-on:task-drop posts that to the
// server, whose re-render then agrees with the moved DOM. A focused grip moves its task
// with the arrow keys the same way.
//
// While dragging, the new-task form just after each list stands in as a place to drop a task
// at the end of that list, and so does the header of a collapsed group, whose list and form
// are hidden. Empty and collapsed lists take a task only these ways, and only on release:
// until then the task stays where it was, so the list doesn't appear and shift the page,
// and a collapsed group stays collapsed. Likewise, when the dragged task leaves a list it
// was the only task of, a spacer of its height keeps that list open until release.
(function () {
  var drag = null;
  var droppedAt = 0;

  function allLists() {
    return Array.prototype.slice.call(document.querySelectorAll('[data-task-list]'));
  }

  // The lists the arrow keys move a task into: all but those of collapsed groups. Empty
  // lists are hidden, so they're found by this, not by layout.
  function openLists() {
    return allLists().filter(function (l) { return !l.closest('.collapsed'); });
  }

  function tasksOf(list, except) {
    return Array.prototype.filter.call(list.children, function (c) {
      return c !== except && c.hasAttribute('data-task-id');
    });
  }

  function position(item) {
    var next = item.nextElementSibling;
    while (next && !next.hasAttribute('data-task-id')) next = next.nextElementSibling;
    return {list: item.parentElement, next: next};
  }

  function drop(item, origin) {
    var p = position(item);
    if (p.list === origin.list && p.next === origin.next) return;
    item.dispatchEvent(new CustomEvent('task-drop', {
      detail: {group: p.list.dataset.group || '', before: p.next ? p.next.dataset.taskId : ''}
    }));
  }

  function shown(el) {
    return el.getClientRects().length > 0;
  }

  // The list, new-task form or collapsed group's header nearest the pointer, counting one
  // it's over as nearest, as {list, zone, pending}: zone is the form or header when that's
  // nearest, and pending is true when the task should go there only on release. A list
  // counts only while it has tasks besides the dragged one, or has the dragged one, so
  // empty lists stay hidden.
  function targetAt(y, item) {
    var best = null, bestDistance = Infinity;
    function consider(el, target) {
      if (!shown(el)) return;
      var r = el.getBoundingClientRect();
      var d = y < r.top ? r.top - y : y > r.bottom ? y - r.bottom : 0;
      if (d < bestDistance) { best = target; bestDistance = d; }
    }
    allLists().forEach(function (l) {
      var group = l.closest('.collapsed');
      if (group) {
        var header = group.querySelector(':scope > header');
        if (header) consider(header, {list: l, zone: header, pending: true});
        return;
      }
      var empty = !tasksOf(l, item).length;
      if (item.parentElement === l || !empty || l.contains(drag.spacer)) consider(l, {list: l, zone: null, pending: false});
      var zone = l.nextElementSibling;
      if (zone && zone.matches('form.new-task')) consider(zone, {list: l, zone: zone, pending: empty});
    });
    return best;
  }

  function highlight(zone) {
    if (drag.zone === zone) return;
    if (drag.zone) drag.zone.classList.remove('drop-target');
    if (zone) zone.classList.add('drop-target');
    drag.zone = zone;
  }

  function follow() {
    var item = drag.item;
    var target = targetAt(drag.y, item);
    highlight(target && target.zone);
    drag.pending = target && target.pending ? target.list : null;
    if (target && !drag.pending) {
      var list = target.list;
      var next = target.zone ? null : tasksOf(list, item).find(function (t) {
        var r = t.getBoundingClientRect();
        return drag.y < r.top + r.height / 2;
      }) || null;
      if (item.parentElement !== list || position(item).next !== next) list.insertBefore(item, next);
    }
    var origin = drag.origin.list;
    if (item.parentElement !== origin && !tasksOf(origin, item).length) {
      if (drag.spacer.parentElement !== origin) origin.appendChild(drag.spacer);
    } else {
      drag.spacer.remove();
    }
    // Keep the task under the pointer, wherever the DOM now puts it.
    item.style.transform = '';
    var top = item.getBoundingClientRect().top;
    item.style.transform = 'translateY(' + (drag.y - drag.offset - top) + 'px)';
  }

  // Scroll while the pointer is near the top or bottom of the window.
  function autoscroll() {
    if (!drag) return;
    var edge = 48, step = 0;
    if (drag.y < edge) step = -Math.ceil((edge - drag.y) / 4);
    else if (drag.y > window.innerHeight - edge) step = Math.ceil((drag.y - window.innerHeight + edge) / 4);
    if (step) { window.scrollBy(0, step); follow(); }
    drag.frame = requestAnimationFrame(autoscroll);
  }

  function end(commit) {
    var d = drag;
    drag = null;
    cancelAnimationFrame(d.frame);
    droppedAt = Date.now();
    d.spacer.remove();
    if (d.zone) d.zone.classList.remove('drop-target');
    d.item.classList.remove('dragging');
    d.item.style.transform = '';
    document.body.classList.remove('dragging-task');
    if (commit && d.pending) d.pending.insertBefore(d.item, null);
    if (commit) drop(d.item, d.origin);
    else d.origin.list.insertBefore(d.item, d.origin.next);
  }

  document.addEventListener('pointerdown', function (e) {
    var grip = e.target.closest('.grip');
    var item = grip && grip.closest('[data-task-list] > [data-task-id]');
    if (!item || drag || e.button !== 0) return;
    e.preventDefault();
    document.body.classList.add('dragging-task');
    var spacer = document.createElement('li');
    spacer.className = 'drag-spacer';
    spacer.style.height = item.offsetHeight + 'px';
    item.classList.add('dragging');
    drag = {item: item, grip: grip, origin: position(item), spacer: spacer, y: e.clientY,
            offset: e.clientY - item.getBoundingClientRect().top};
    grip.setPointerCapture(e.pointerId);
    follow();
    drag.frame = requestAnimationFrame(autoscroll);
  });

  document.addEventListener('pointermove', function (e) {
    if (!drag) return;
    drag.y = e.clientY;
    follow();
  });

  // The click that follows a drop lands on whatever is under the pointer once the task has
  // moved, often the task's row, which would expand it; swallow it before it gets there.
  document.addEventListener('click', function (e) {
    if (Date.now() - droppedAt < 500) {
      droppedAt = 0;
      e.stopPropagation();
      e.preventDefault();
    }
  }, true);

  document.addEventListener('pointerup', function () { if (drag) end(true); });
  document.addEventListener('pointercancel', function () { if (drag) end(false); });
  document.addEventListener('keydown', function (e) {
    if (drag && e.key === 'Escape') { end(false); return; }
    var grip = e.target.closest && e.target.closest('.grip');
    var item = grip && grip.closest('[data-task-list] > [data-task-id]');
    if (!item || drag || (e.key !== 'ArrowUp' && e.key !== 'ArrowDown')) return;
    e.preventDefault();
    var origin = position(item);
    var lists = openLists();
    var siblings = tasksOf(origin.list, item);
    var i = siblings.indexOf(origin.next);
    if (i < 0) i = siblings.length;
    if (e.key === 'ArrowUp') {
      if (i > 0) origin.list.insertBefore(item, siblings[i - 1]);
      else {
        var prev = lists[lists.indexOf(origin.list) - 1];
        if (!prev) return;
        prev.insertBefore(item, null);
      }
    } else {
      if (i < siblings.length) origin.list.insertBefore(item, siblings[i + 1] || null);
      else {
        var next = lists[lists.indexOf(origin.list) + 1];
        if (!next) return;
        next.insertBefore(item, tasksOf(next, item)[0] || null);
      }
    }
    grip.focus();
    drop(item, origin);
  });
})();
