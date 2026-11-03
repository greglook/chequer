// Which sections of each list and plan this browser has collapsed, kept in localStorage.
//
// Each list or plan has one key, chequer:collapsed:<owner>, where owner is like 'list-5' or
// 'plan-3', holding {at, sections}: the time in milliseconds it was last opened or had a
// section toggled, and the element ids of its collapsed sections. A key goes away when its
// last section is expanded, and prune, which the index pages call, removes those not opened
// for 30 days. Storage can be unavailable, so every access tolerates failure.
var chequerCollapse = (function () {
  var prefix = 'chequer:collapsed:';
  var maxAge = 30 * 24 * 60 * 60 * 1000;
  var opened = {};

  function read(owner) {
    try {
      var v = JSON.parse(localStorage.getItem(prefix + owner));
      if (v && Array.isArray(v.sections)) return v;
    } catch (e) {}
    return null;
  }

  function write(owner, sections) {
    try {
      if (sections.length) {
        localStorage.setItem(prefix + owner, JSON.stringify({at: Date.now(), sections: sections}));
      } else {
        localStorage.removeItem(prefix + owner);
      }
    } catch (e) {}
  }

  // True when owner's section element-id is collapsed. The first call for an owner on a page
  // counts as opening it.
  function isCollapsed(owner, id) {
    var v = read(owner);
    if (!v) return false;
    if (!opened[owner]) {
      opened[owner] = true;
      write(owner, v.sections);
    }
    return v.sections.indexOf(id) !== -1;
  }

  function set(owner, id, collapsed) {
    var v = read(owner);
    var sections = (v ? v.sections : []).filter(function (s) { return s !== id; });
    if (collapsed) sections.push(id);
    write(owner, sections);
  }

  // Collapse or expand every section of owner on the page, those marked data-collapse-owner.
  function setAll(owner, collapsed) {
    var sections = [];
    if (collapsed) {
      var els = document.querySelectorAll('[data-collapse-owner="' + owner + '"]');
      for (var i = 0; i < els.length; i++) sections.push(els[i].id);
    }
    write(owner, sections);
  }

  // Remove the entries of lists and plans not opened in 30 days, and any that don't parse,
  // such as the per-section keys of earlier versions.
  function prune() {
    try {
      var now = Date.now();
      for (var i = localStorage.length - 1; i >= 0; i--) {
        var k = localStorage.key(i);
        if (k && k.indexOf(prefix) === 0) {
          var v = read(k.slice(prefix.length));
          if (!v || typeof v.at !== 'number' || now - v.at > maxAge) localStorage.removeItem(k);
        }
      }
    } catch (e) {}
  }

  return {isCollapsed: isCollapsed, set: set, setAll: setAll, prune: prune};
})();
