(ns replumb.repl
  (:refer-clojure :exclude [load-file])
  (:require-macros [cljs.env.macros :refer [with-compiler-env]])
  (:require [cljs.js :as cljs]
            [cljs.tagged-literals :as tags]
            [cljs.tools.reader :as r]
            [cljs.analyzer :as ana]
            [cljs.env :as env]
            [cljs.repl :as cljs-repl]
            [cljs.pprint :refer [pprint]]
            [replumb.load :as load]
            [replumb.common :as common]))

;;;;;;;;;;;;;
;;; State ;;;
;;;;;;;;;;;;;

;; This is the compiler state atom. Note that cljs/eval wants exactly an atom.
(defonce st (cljs/empty-state))

(defonce app-env (atom {:current-ns 'cljs.user
                        :last-eval-warning nil
                        :initializing? false
                        :needs-init? true}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Util fns - many from mfikes/plank ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ex-info-data "The ex-info data for this file" {:tag ::error})

(defn debug-prn
  [& args]
  (binding [cljs.core/*print-fn* cljs.core/*print-err-fn*]
    (apply println args)))

(defn current-ns
  "Return the current namespace, as a symbol."
  []
  (:current-ns @app-env))

(defn known-namespaces
  []
  (keys (:cljs.analyzer/namespaces @st)))

(defn remove-ns
  "Removes the namespace named by the symbol."
  ([ns]
   (remove-ns env/*compiler* ns))
  ([state ns]
   {:pre [(symbol? ns)]}
   (swap! state update-in [:cljs.analyzer/namespaces] dissoc ns)))

(defn map-keys
  [f m]
  (reduce-kv (fn [r k v] (assoc r (f k) v)) {} m))

(defn repl-read-string
  [line]
  (r/read-string {:read-cond :allow :features #{:cljs}} line))

(defn ns-form?
  [form]
  (and (seq? form) (= 'ns (first form))))

(defn extract-namespace
  [source]
  (let [first-form (repl-read-string source)]
    (when (ns-form? first-form)
      (second first-form))))

(defn resolve
  "From cljs.analizer.api.clj. Given an analysis environment resolve a
  var. Analogous to clojure.core/resolve"
  [opts env sym]
  {:pre [(map? env) (symbol? sym)]}
  (try
    (when (:verbose opts)
      (debug-prn "Calling cljs.analyzer/resolve-var..."))
    (ana/resolve-var env sym ana/confirm-var-exist-warning)
    (catch :default e
      (when (:verbose opts)
        (debug-prn "Exception caught in resolve: " e))
      (try
        (when (:verbose opts)
          (debug-prn "Calling cljs.analyzer/resolve-macro-var..."))
        (ana/resolve-macro-var env sym)
        (catch :default e
          (when (:verbose opts)
            (debug-prn "Exception caught in resolve: " e)))))))

(defn get-var
  [opts env sym]
  (let [var (with-compiler-env st (resolve opts env sym))
        var (or var
              (if-let [macro-var (with-compiler-env st
                                   (resolve opts env (symbol "cljs.core$macros" (name sym))))]
                (update (assoc macro-var :ns 'cljs.core)
                  :name #(symbol "cljs.core" (name %)))))]
    (if (= (namespace (:name var)) (str (:ns var)))
      (update var :name #(symbol (name %)))
      var)))

(def replumb-repl-special-set
  '#{in-ns require require-macros import load-file doc source pst})

(defn repl-special?
  [form]
  (and (seq? form) (replumb-repl-special-set (first form))))

(def valid-opts-set
  "Set of valid option for external input validation:

  * :verbose If true, enables more traces."
  #{:verbose :load-fn! :no-warning-error})

(defn valid-opts
  "Extract options according to the valid-opts-set."
  [opts]
  (into {} (filter (comp valid-opts-set first) opts)))

(defn make-base-eval-opts!
  "Gets the base set of evaluation options. The variadic arity function
  works like merge, the mapping from the latter (left-to-right) will be
  the mapping in the result. Extracts the options in the valid-options
  set."
  ([]
   (make-base-eval-opts! {}))
  ([dynamic-opts]
   {:ns (:current-ns @app-env)
    :context :expr
    :source-map false
    :def-emits-var true
    :load (or (:load-fn! dynamic-opts) load/js-default-load)
    :eval cljs/js-eval
    :verbose (or (:verbose dynamic-opts) false)}))

(defn self-require?
  [specs]
  (some
    (fn [quoted-spec-or-kw]
      (and (not (keyword? quoted-spec-or-kw))
        (let [spec (second quoted-spec-or-kw)
              ns (if (sequential? spec)
                   (first spec)
                   spec)]
          (= ns @current-ns))))
    specs))

(defn canonicalize-specs
  [specs]
  (letfn [(canonicalize [quoted-spec-or-kw]
            (if (keyword? quoted-spec-or-kw)
              quoted-spec-or-kw
              (as-> (second quoted-spec-or-kw) spec
                (if (vector? spec) spec [spec]))))]
    (map canonicalize specs)))

(defn process-reloads!
  [specs]
  (if-let [k (some #{:reload :reload-all} specs)]
    (let [specs (->> specs (remove #{k}))]
      (if (= k :reload-all)
        (reset! cljs.js/*loaded* #{})
        (apply swap! cljs.js/*loaded* disj (map first specs)))
      specs)
    specs))

(defn make-ns-form
  [kind specs target-ns]
  (if (= kind :import)
    (with-meta `(~'ns ~target-ns
                  (~kind
                    ~@(map (fn [quoted-spec-or-kw]
                             (if (keyword? quoted-spec-or-kw)
                               quoted-spec-or-kw
                               (second quoted-spec-or-kw)))
                        specs)))
      {:merge true :line 1 :column 1})
    (with-meta `(~'ns ~target-ns
                  (~kind
                    ~@(-> specs canonicalize-specs process-reloads!)))
      {:merge true :line 1 :column 1})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Callback handling fns ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn success-map
  "Builds the map to return when the evaluation returned success.
  Supports the following options:

  * :no-pr-str-on-value avoids wrapping value in pr-str."
  ([opts form value]
   {:success? true
    :form form
    :value (if-not (:no-pr-str-on-value opts)
             (pr-str value)
             value)}))

(defn error-map
  "Builds the map to return when the evaluation returned error."
  ([opts form error]
   {:success? false
    :form form
    :error error}))

(defn reset-last-warning!
  []
  (swap! app-env assoc :last-eval-warning nil))

(defn custom-warning-handler
  "Handles the case when the evaluation returns a warning and can be
  passed as a warning handler when partially applied. At the moment it
  treats warnings as errors."
  [opts cb warning-type env extra]
  (when (:verbose opts)
    (debug-prn (str "Handling warning:\n" (with-out-str (pprint {:warning-type warning-type
                                                                 :env env
                                                                 :extra extra})))))
  (when (warning-type ana/*cljs-warnings*)
    (when-let [s (ana/error-message warning-type extra)]
      (swap! app-env assoc :last-eval-warning (ana/message env s)))))

(defn validated-call-back!
  [call-back! res]
  {:pre [(map? res)
         (find res :form)
         (or (find res :error) (find res :value))
         (or (and (find res :value) (get res :success?))
             (and (find res :error) (not (get res :success?))))
         (or (and (find res :value) (string? (get res :value)))
             (and (find res :error) (instance? js/Error (get res :error))))]}
  (call-back! res))

(defn call-side-effect!
  "Execute the correct side effecting function from data.
  Handles :side-effect!, :on-error! and on-success!."
  [data {:keys [value error]}]
  (if-let [f! (:side-effect! data)]
    (f!)
    (if-not error
      (when-let [s! (:on-success! data)] (s!))
      (when-let [e! (:on-error! data)] (e!)))))

(defn warning-error-map!
  "Checks if there has been a warning and if so will return the correct
  error map instead of the input one. Note that if the input map was
  already an :error, the warning will be ignored.
  If :no-warning-error is true in opts the warning remains a warning,
  not emitting errors."
  [opts {:keys [value error] :as original-res}]
  (if (or error (:no-warning-error opts))
    original-res
    (if-let [warning-msg (:last-eval-warning @app-env)]
      (let [warning-error (ex-info warning-msg ex-info-data)]
        (when (:verbose opts)
          (debug-prn "Erroring on last warning: " warning-msg))
        (common/wrap-error warning-error))
      original-res)))

(defn call-back!
  "Handles the evaluation result, calling the callback in the right way,
  based on the success or error of the evaluation. The res parameter
  expects the same map as ClojureScript's cljs.js callback,
  :value if success and :error if not. The data parameter might contain
  additional stuff:

  * :form the source form that has been eval-ed
  * :on-success! 0-arity function that will be executed on success
  * :on-error! 0-arity function that will be executed on error
  * :side-effect! 0-arity function that if present will be executed for
    both success and error, effectively disabling the individual
    on-success!  and on-error!

  Call-back! supports the following opts:

  * :verbose will enable the the evaluation logging, defaults to false.
  * :no-pr-str-on-value avoids wrapping successful value in a pr-str
  * :no-warning-error will consider a warning like a warning, not
  emitting errors

  Notes:
  1. The opts map passed here overrides the environment options.
  2. This function will also clear the :last-eval-warning flag in
  app-env.
  3. It will execute (:side-effect!) or (on-success!)  and (on-error!)
  *before* the callback is called.

  ** Every function in this namespace should call call-back! as
  single point of exit. **"
  ([opts cb res]
   (call-back! opts cb {} res))
  ([opts cb data res]
   (when (:verbose opts)
     (debug-prn "Calling back!\n" (with-out-str (pprint {:opts opts :data data :res res}))))
   (let [new-map (warning-error-map! opts res)]
     (let [{:keys [value error]} new-map]
       (call-side-effect! data new-map)
       (reset-last-warning!)
       (if-not error
         (do (set! *e nil)
             (cb (success-map opts (:form data) value)))
         (do (set! *e error)
             (cb (error-map opts (:form data) error))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Processing fns - from mfikes/plank ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn process-require
  [opts cb data kind specs]
  ;; TODO - cannot find a way to handle (require something) correctly, note no quote
  (if-not (= 'quote (ffirst specs))
    (call-back! opts cb data (common/error-argument-must-be-symbol "require" ex-info-data))
    (let [is-self-require? (and (= :kind :require) (self-require? specs))
          [target-ns restore-ns] (if-not is-self-require?
                                   [(:current-ns @app-env) nil]
                                   ['cljs.user (:current-ns @app-env)])
          ns-form (make-ns-form kind specs target-ns)]
      (when (:verbose opts)
        (debug-prn "Processing" kind "via" (pr-str ns-form)))
      (cljs/eval st
                 ns-form
                 (make-base-eval-opts! opts)
                 (fn [error]
                   (call-back! opts cb
                               (merge data
                                      {:side-effect! #(when is-self-require?
                                                        (swap! app-env assoc :current-ns restore-ns))})
                               (if error
                                 error
                                 (common/wrap-success nil))))))))

(defn process-doc
  [opts cb data env sym]
  (call-back! (merge opts {:no-pr-str-on-value true})
              cb
              data
              (common/wrap-success
               (with-out-str (cljs-repl/print-doc (get-var opts env sym))))))

(defn process-pst
  [opts cb data expr]
  (if-let [expr (or expr '*e)]
    (cljs/eval st
               expr
               (make-base-eval-opts! opts)
               (fn [res]
                 (let [[opts msg] (if res
                                    [(assoc opts :no-pr-str-on-value true) (common/extract-message res true true)]
                                    [opts res])]
                   (call-back! opts cb data (common/wrap-success msg)))))
    (call-back! opts cb data (common/wrap-success nil))))

(defn process-in-ns
  [opts cb data ns-string]
  (cljs/eval
   st
   ns-string
   (make-base-eval-opts! opts)
   (fn [result]
     (if (and (map? result) (:error result))
       (call-back! opts cb data result)
       (let [ns-symbol result]
         (when (:verbose opts)
           (debug-prn "in-ns argument is symbol? " (symbol? ns-symbol)))
         (if-not (symbol? ns-symbol)
           (call-back! opts cb data (common/error-argument-must-be-symbol "in-ns" ex-info-data))
           (if (some (partial = ns-symbol) (known-namespaces))
             (call-back! opts cb
                         (merge data {:side-effect! #(swap! app-env assoc :current-ns ns-symbol)})
                         (common/wrap-success nil))
             (let [ns-form `(~'ns ~ns-symbol)]
               (cljs/eval
                st
                ns-form
                (make-base-eval-opts! opts)
                (fn [error]
                  (call-back! opts
                              cb
                              (merge data {:on-success! #(swap! app-env assoc :current-ns ns-symbol)})
                              (if error
                                (common/wrap-error error)
                                (common/wrap-success nil)))))))))))))

(defn process-repl-special
  [opts cb data expression-form]
  (let [env (assoc (ana/empty-env) :context :expr
                   :ns {:name (:current-ns @app-env)})
        argument (second expression-form)]
    (case (first expression-form)
      in-ns (process-in-ns opts cb data argument)
      require (process-require opts cb data :require (rest expression-form))
      require-macros (process-require opts cb data :require-macros (rest expression-form))
      import (process-require opts cb data :import (rest expression-form))
      doc (process-doc opts cb data env argument)
      source (call-back! opts cb data (common/error-keyword-not-supported "source" ex-info-data)) ;; (println (fetch-source (get-var env argument)))
      pst (process-pst opts cb data argument)
      load-file (call-back! opts cb data (common/error-keyword-not-supported "load-file" ex-info-data))))) ;; (process-load-file argument opts)

(defn process-1-2-3
  [data expression-form value]
  (when-not (or ('#{*1 *2 *3 *e} expression-form)
                (ns-form? expression-form))
    (set! *3 *2)
    (set! *2 *1)
    (set! *1 value)))

;;;;;;;;;;;;;;;;;;;;
;;; External API ;;;
;;;;;;;;;;;;;;;;;;;;

(defn init-repl!
  "The init-repl function."
  [opts]
  (when (:verbose opts)
    (debug-prn "Initializing REPL environment..." ))
  (assert (= cljs.analyzer/*cljs-ns* 'cljs.user))
  #_(set! *target* "default")
  (set! (.. js/window -cljs -user) #js {}))

(defn update-to-initializing
  [old-app-env]
  (if (and (not (:initializing? old-app-env))
           (:needs-init? old-app-env))
    (assoc old-app-env :initializing? true)
    (assoc old-app-env :needs-init? false)))

(defn update-to-initialized
  [old-app-env]
  {:pre [(:needs-init? old-app-env) (:initializing? old-app-env)]}
  (merge old-app-env {:initializing? false
                      :needs-init? false}))

(defn init-repl-if-necessary!
  [opts cb]
  (when (:needs-init? (swap! app-env update-to-initializing))
    (do (init-repl! opts)
        (swap! app-env update-to-initialized))))

(defn read-eval-call
  "Reads, evaluates and calls back with the evaluation result.

  The first parameter is a map of configuration options, currently
  supporting:

  * :verbose  will enable the the evaluation logging, defaults to false.
  * :load-fn! overrides the ClojureScript's *load-fn*

  The second parameter cb, should be a 1-arity function which receives
  the result map.

  Therefore, given cb (fn [result-map] ...), the main map keys are:

  :success? ;; a boolean indicating if everything went right
  :value    ;; (if (success? result)) will contain the actual yield of the evaluation
  :error    ;; (if (not (success? result)) will contain a js/Error
  :form     ;; the evaluated form as data structure (not a string)

  It initializes the repl harness if necessary."
  [opts cb source]
  (try
    (let [expression-form (repl-read-string source)
          opts (valid-opts opts)
          data {:form expression-form}]
      (init-repl-if-necessary! opts cb)
      (when (:verbose opts)
        (debug-prn "Evaluating " expression-form " with options " opts))
      (binding [ana/*cljs-warning-handlers* [(partial custom-warning-handler opts cb)]]
        (if (repl-special? expression-form)
          (process-repl-special opts cb data expression-form)
          (cljs/eval-str st
                         source
                         source
                         ;; opts (map)
                         (make-base-eval-opts! opts)
                         (fn [res]
                           (when (:verbose opts)
                             (debug-prn "Evaluation returned: " res))
                           (call-back! opts cb
                                       (merge data
                                              {:on-success! #(do (process-1-2-3 data expression-form (:value res))
                                                                 (swap! app-env assoc :current-ns (:ns res)))})
                                       res))))))
    (catch :default e
      (when (:verbose opts)
        (debug-prn "Exception caught in read-eval-call: " e))
      (call-back! opts cb {} (common/wrap-error e)))))

(defn reset-env!
  "It dons the following (in order):

  1. remove the input namespaces from the compiler environment
  2. set *e to nil
  3. reset the last warning
  4. in-ns to cljs.user

  It accepts a sequence of symbols or strings."
  ([]
   (reset-env! nil))
  ([namespaces]
   (doseq [ns namespaces]
     (remove-ns st (symbol ns)))
   (reset-last-warning!)
   (read-eval-call {} identity "(set! *e nil)")
   (read-eval-call {} identity "(in-ns 'cljs.user)")))
