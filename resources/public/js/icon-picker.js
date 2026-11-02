// The emoji picker every icon field on the page shares.
//
// The layout renders one #icon-picker, outside the live content, and each icon field is just
// an input inside a .icon-field. Clicking such an input opens the picker next to it, with
// an empty search, and its search box gets an icon-picker-open event, on which Datastar
// fetches the results. Picking an emoji fills the input that opened the picker and closes
// it; so does Enter in the search box, with the first result. Clear icon empties the input.
// Escape, or a click outside the picker and its input, closes it.
(function () {
  var input = null;

  function picker() {
    return document.getElementById('icon-picker');
  }

  function search() {
    return picker().querySelector('input[type=search]');
  }

  // Place the picker below input, or above it when there's no room below, within the
  // viewport. It's fixed, not absolute, so it never changes the page's height, which could
  // add or remove the scrollbar and move the input out from under it.
  function place() {
    var p = picker();
    var r = input.getBoundingClientRect();
    var root = document.documentElement;
    var top = r.bottom + 4;
    if (top + p.offsetHeight > root.clientHeight && r.top - 4 - p.offsetHeight >= 0) {
      top = r.top - 4 - p.offsetHeight;
    }
    p.style.left = Math.max(8, Math.min(r.left, root.clientWidth - p.offsetWidth - 8)) + 'px';
    p.style.top = top + 'px';
  }

  function replace() {
    if (!input) return;
    if (input.isConnected) place();
    else close(false);
  }

  function open(el) {
    input = el;
    picker().hidden = false;
    place();
    var s = search();
    s.value = '';
    s.focus();
    s.dispatchEvent(new CustomEvent('icon-picker-open'));
  }

  function close(refocus) {
    if (!input) return;
    picker().hidden = true;
    if (refocus && input.isConnected) input.focus();
    input = null;
  }

  function fill(value) {
    if (input && input.isConnected) {
      input.value = value;
      input.dispatchEvent(new Event('input', {bubbles: true}));
      input.dispatchEvent(new Event('change', {bubbles: true}));
    }
    close(true);
  }

  document.addEventListener('click', function (e) {
    var p = picker();
    if (!p) return;
    var field = e.target.closest('.icon-field input[name=icon]');
    if (field) {
      if (field !== input) open(field);
      return;
    }
    if (!p.contains(e.target)) {
      close(false);
      return;
    }
    var emoji = e.target.closest('[data-emoji]');
    if (emoji) fill(emoji.dataset.emoji);
    else if (e.target.closest('button.clear')) fill('');
  });

  document.addEventListener('keydown', function (e) {
    if (!input) return;
    if (e.key === 'Escape') {
      close(true);
    } else if (e.key === 'Enter' && e.target === search()) {
      e.preventDefault();
      var first = picker().querySelector('[data-emoji]');
      if (first) fill(first.dataset.emoji);
    }
  });

  window.addEventListener('resize', replace);
  // capture, to hear scrolling of any element the input is in
  window.addEventListener('scroll', replace, true);
  // results arriving change the picker's height, which can move it above the input
  if (window.ResizeObserver) {
    document.addEventListener('DOMContentLoaded', function () {
      new ResizeObserver(replace).observe(picker());
    });
  }
})();
