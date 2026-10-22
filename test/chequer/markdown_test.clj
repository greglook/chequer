(ns chequer.markdown-test
  (:require
    [chequer.markdown :as md]
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]))


(deftest common-markup-renders
  (is (= "<p>a <em>b</em> <strong>c</strong> <code>d</code></p>\n" (md/render "a *b* **c** `d`")))
  (is (= "<ul>\n<li>one</li>\n<li>two</li>\n</ul>\n" (md/render "- one\n- two")))
  (is (= "<pre><code class=\"language-clj\">(+ 1 2)\n</code></pre>\n" (md/render "```clj\n(+ 1 2)\n```")))
  (is (= "<h2>Title</h2>\n" (md/render "## Title")))
  (is (= "" (md/render nil))))


(deftest raw-html-is-escaped
  (is (= "<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>\n" (md/render "<script>alert(1)</script>")))
  (is (= "<p>&lt;img src=x onerror=alert(1)&gt;</p>\n" (md/render "<img src=x onerror=alert(1)>")))
  (is (= "<p>&lt;!-- c --&gt;</p>\n" (md/render "<!-- c -->")))
  (is (not (str/includes? (md/render "<div>\n<b>x</b>\n</div>") "<b>"))))


(deftest safe-links-are-kept
  (is (= "<p><a href=\"https://example.com/a?b=1&amp;c=2\">x</a></p>\n"
         (md/render "[x](https://example.com/a?b=1&c=2)")))
  (is (= "<p><a href=\"/plans/7\">x</a></p>\n" (md/render "[x](/plans/7)")))
  (is (= "<p><a href=\"page#top\">x</a></p>\n" (md/render "[x](page#top)")))
  (is (= "<p><a href=\"MAILTO:a@b.c\">x</a></p>\n" (md/render "[x](MAILTO:a@b.c)")))
  (is (= "<p><a href=\"http://e.com\">http://e.com</a></p>\n" (md/render "<http://e.com>")))
  (is (= "<p><a href=\"mailto:a@b.c\">a@b.c</a></p>\n" (md/render "<a@b.c>"))))


(deftest unsafe-urls-are-neutralized
  (doseq [url ["javascript:alert(1)" "JavaScript:alert(1)" "data:text/html,x" "vbscript:x"
               "java\u0001script:alert(1)" "<javascript:alert(1)>"]]
    (is (= "<p><a href=\"#\">x</a></p>\n" (md/render (str "[x](" url ")"))) url)
    (is (= "<p><img src=\"#\" alt=\"x\" /></p>\n" (md/render (str "![x](" url ")"))) url))
  (is (= "<p>&lt;javascript:alert(1)&gt;</p>\n" (md/render "<javascript:alert(1)>"))
      "an autolink with an unsafe scheme stays text"))


(deftest attributes-are-escaped
  (is (= "<p><a href=\"/a&quot;b\" title=\"t&lt;&gt;&#39;&amp;\">x</a></p>\n"
         (md/render "[x](</a\"b> \"t<>'&\")")))
  (is (= "<p><img src=\"/i.png\" alt=\"a&quot; onerror=&quot;x\" /></p>\n"
         (md/render "![a\" onerror=\"x](/i.png)")))
  (is (= "<pre><code class=\"language-&lt;x&gt;\">y\n</code></pre>\n" (md/render "```<x>\ny\n```"))))
