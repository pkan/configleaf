(ns configleaf.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [stencil.core :as stencil]
            [leiningen.core.project :as project])
  (:import java.util.Properties))

;;
;; Functions that provide functionality independent of any one build tool.
;;

(defn read-current-profiles
  "Read the current profiles from the config file for the project
   at the given path. For example, if the argument is \"/home/david\", reads
   \"/home/david/.configleaf/current\". .configleaf/current should contain a
   single form that lists the currently active profiles."
  [config-store-path]
  (let [config-file (io/file config-store-path ".configleaf/current")]
    (when (and (.exists config-file)
               (.isFile config-file))
      (binding [*read-eval* false]
        (read-string (slurp config-file))))))

(defn save-current-profiles
  "Store the given profiles as the new current profiles for the project
   at the given path."
  [config-store-path current-profiles]
  (let [config-dir (io/file config-store-path ".configleaf")
        config-file (io/file config-store-path ".configleaf/current")]
    (if (not (and (.exists config-dir)
                  (.isDirectory config-dir)))
      (.mkdir config-dir))
    (spit config-file (prn-str current-profiles))))

(defn get-current-profiles
  "Return the current profiles (list of names) from the saved file.
   Otherwise, return nil."
  []
  (read-current-profiles "."))

(defn- fixed-point
  "Calls the function argument on the initial value, and then on the
   result of function call, and so on, until the output value does not
   change. You should be sure that func is a contraction mapping, or it
   will loop forever."
  [func initial]
  (loop [input initial]
    (let [output (func input)]
      (if (= input output) output (recur output)))))

(defn expand-profile
  "Given a project map and a profile in the project map, expands the profile
   into a seq of its constituent profiles. Composite profiles are expanded one
   step, basic profiles are returned as the only member of the sequence."
  [project profile]
  (let [profile-val (get-in project [:profiles profile])]
    (if (vector? profile-val)
      profile-val
      [profile])))

(defn fully-expand-profile
  "Reduce a given profile into a set of the names of its basic profiles."
  [project profile]
  (fixed-point (fn [profiles]
                 (->> (map (partial expand-profile project) profiles)
                      (flatten)
                      ;; Append the flattened list of generated profiles onto
                      ;; a list of all the profiles we've found so far that are
                      ;; not composite (We just expanded the ones that are).
                      (concat (filter #(not (vector? (get-in project
                                                             [:profiles %])))
                                      profiles))
                      (into #{})))
               [profile]))

(defn unstickable-profiles
  "Return all of the sets of the name keys of basic profiles that
   cannot be made sticky according to the :configleaf configuration
   key. Composite profiles are expanded away. Returns a set of the
   sets of basic keys that cannot be set sticky in combination."
   [project]
  (let [unstickable-profiles (into #{}
                                   (get-in project
                                           [:configleaf :never-sticky]))]
    (into #{} (map (partial fully-expand-profile project)
                   unstickable-profiles))))

(defn print-current-sticky-profiles
  "Given a set of profiles as an argument, print them in a standard way
   to stdout."
  [profiles]
  (if (not (empty? profiles))
    (println "Current sticky profiles:" profiles)
    (println "Current sticky profiles:")))

(defn valid-profile?
  "Returns true if profile is a profile listed in the project profiles."
  [project profile]
  (contains? (apply hash-set (keys (:profiles project)))
             profile))

(defn config-namespace
  "Get the namespace to put the profile info into, or use the default
   (which is cfg.current)."
  [project]
  (or (get-in project [:configleaf :namespace])
      'cfg.current))

(defn merge-profiles
  "Given the project map and a list of profile names, merge the profiles into
   the project map and return the result. Will check the :included-profiles
   metadata key to ensure that profiles are not merged multiple times."
  [project profiles]
  (let [included-profiles (into #{} (:included-profiles (meta project)))
        profiles-to-add (filter #(and (not (contains? included-profiles %))
                                      (contains? (:profiles project) %))
                                profiles)]
    (project/merge-profiles project profiles-to-add)))

(defn namespace-to-filepath
  "Given a namespace as a string/symbol, return a string containing the path
   where we'd look for its source."
  [ns-name]
  (str (string/replace ns-name
                       #"[.-]"
                       {"." "/", "-" "_"})
       ".clj"))

(defn require-config-namespace
  "Put the configured project map (according to given project) into
  a var in the config namespace (in the current JVM). Effect is as if
  you have 'require'd the namespace, but there is no clj file involved
  in the process.

  Note the argument is a string. You need to print the project map to a string
  to pass it into this function. This is so the string can survive leiningen 2's
  own macro handling, which does not prevent a project map from being evaluated,
  which you usually can't do, due to symbols and lists that would not eval."
  [project-as-str project-metadata-as-str]
  (let [project (read-string project-as-str)
        project-metadata (read-string project-metadata-as-str)
        cl-ns-name (symbol (config-namespace project))]
    (create-ns cl-ns-name)
    (intern cl-ns-name (with-meta 'project project-metadata) project)))

(defn output-config-namespace
  "Write a Clojure file that will set up the config namespace with the project
   in it when it is loaded. Returns the project with the profiles merged."
  [project]
  (let [ns-name (str (config-namespace project))
        src-path (or (get-in project [:configleaf :config-source-path])
                     (first (:source-paths project))
                     "src/")
        ns-file (io/file src-path
                         (namespace-to-filepath ns-name))
        ns-parent-dir (.getParentFile ns-file)]
    (if (not (.exists ns-parent-dir))
      (.mkdirs ns-parent-dir))
    (spit ns-file
          (binding [*print-dup*    false
                    *print-meta*   false
                    *print-length* nil
                    *print-level*  nil]
            (stencil/render-file
             "templates/configleafns"
             {:namespace ns-name
              :project project
              :project-metadata (select-keys (meta project)
                                             [:without-profiles
                                              :included-profiles])})))))

(defn set-system-properties
  "Given a map of string keys to string values, sets the Java properties named
   by the keys to have the value in the corresponding value."
  [properties]
  (let [props (Properties. (System/getProperties))]
    (doseq [[k v] properties]
      (try
        (.setProperty props k v)
        (catch Exception e
          (println "Configleaf")
          (println "  Java property keys and values must both be strings.")
          (println (str "  Skipping key: " k " value: " v)))))
    (System/setProperties props)))

(defn check-gitignore
  "Check the .gitignore file for the project to see if .configleaf/ is ignored,
   and warn if it isn't. Also check for the config-namespace source file."
  [cl-config]
  (try
    (let [gitignore-file (io/file ".gitignore")
          gitignore-lines (string/split-lines (slurp gitignore-file))
          ns-name (config-namespace cl-config)
          ns-filepath (namespace-to-filepath ns-name)
          config-ns-re (re-pattern (str "\\Qsrc/" ;; Quote whole thing.
                                        ns-filepath
                                        "\\E"))]
      (when (not (some #(re-matches #"\.configleaf" %) gitignore-lines))
        (println "Configleaf")
        (println "  The .configleaf directory does not appear to be present in")
        (println "  .gitignore. You almost certainly want to add it."))
      (when (not (some #(re-matches config-ns-re %) gitignore-lines))
        (println "Configleaf")
        (println "  The Configleaf namespace file," (str "src/" ns-filepath)
                 ", does not")
        (println "  appear to be present in .gitignore. You almost certainly")
        (println "  want to add it.")))
    ;; For now, just be quiet if we can't open the file.
    (catch Exception e)))