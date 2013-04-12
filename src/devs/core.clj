(ns devs.core)

(defn- alternatives
  [state fs]
  (if (empty? fs)
    state
    (if-let [ret ((first fs) state)]
      ret
      (recur state (rest fs)))))

(defn- sequential
  [state fs]
  (if (empty? fs)
    state
    (if-let [ret ((first fs) state)]
      (recur ret (rest fs)))))

(defn- lift-sequential
  [fs]
  (fn [machine] (sequential machine (seq fs))))

(defn in-state?
  "in-state? returns a predicate. Later, when that predicate is applied to a state machine,
   it ensures that the machine is in the labeled state. If so, the predicate returns the
   entire machine. If not, it returns nil."
  [label]
  (fn [current]
    (if (= (:state current) label)
      current
      nil)))

(defn side-effect
  "side-effect returns a thunk to be applied later. When the thunk is applied to a state machine,
   it calls the original function as (apply f state args). The side effecting function MUST
   return a new value for the state machine if processing is to continue. The side effecting
   function MAY return nil to abort processing."
  [f & args]
  (fn [current]
    (apply f current args)))

(defn guard
  "guard returns a thunk to be applied later. When the thunk is applied,
   it evaluates (apply guard-fn state). If the result is true, it continues
   processing with true-fn. If false and false-fn is provided, then false-fn
   is evaluated."
  ([guard-fn true-fn] (guard guard-fn true-fn (constantly nil)))
  ([guard-fn true-fn false-fn]
     (fn [current]
       ((if (guard-fn current) true-fn false-fn) current))))

(defn new-state
  "new-state returns a thunk to be applied later. When the thunk is applied to a state machine,
   it causes the machine to transition into the given state."
  [next-label]
  (fn [current]
    (when-not (some #{next-label} (:state-alphabet current))
      (throw (ex-info "Unrecognized state symbol" {:state-machine current :next-label next-label})))
    (assoc current :state next-label)))

(defn generate-event
  "generate-event returns a thunk to be applied later. When the thunk is applied to a state machine,
   it causes the machine to chain together another automatic transition. This is needed when you
   are doing too many side-effects, so be wary."
  [next-state]
  (fn [current]
    (update-in current [:internal-events] conj next-state)))

(defn on-event
  "This is a builder function that constructs the transition function incrementally.
   Given a state machine, it adds a partial function that is activated when the input symbol 'evt'
   is presented. The forms should be a sequence of guard, side-effect, and new-state thunks."
  [machine evt & forms]
  (update-in machine [:transitions evt] conj forms))

;; TODO - reuse alternative/sequential mechanism for output function
(defn outputs
  "Append an incomplete output function to the state machine's output generator. Example:

   (outputs machine :waiting (generate :read))

   The state machine can generate output after every input, whether the input was external or
   internally initiated. The output is consed onto the list (:output machine), so the history
   of all outputs is available."
  [machine state & forms]
  (update-in machine [:output-function state] conj forms))

;;; TODO - allow _output_ to be a thunk. Call it when needed.
(defn generate
  "Use this together with outputs."
  [output]
  (fn [current]
    (update-in current [:output] conj output)))

(defn- apply-alternatives
  [machine alts]
    (if-not alts machine)
    (if-let [ret (alternatives machine (map lift-sequential alts))]
      ret
      machine))

(defn- select-future
  [machine input]
  (apply-alternatives machine (get-in machine [:transitions input])))

(defn- write-outputs
  [machine]
  (apply-alternatives machine (get-in machine [:output-function (:state machine)])))

(defn evolve
  "Evolve the state machine, given an input. Returns a new state machine as modified by
   the transition function and the output function. When automatic transitions are applied,
   evolve returns the final output. The intermediate states are not accessible."
  [machine input]
  (when-not (some #{input} (:input-alphabet machine))
    (ex-info "Unrecognized input symbol" {:state-machine machine :input input}))
  (let [next-state  (update-in machine [:input-history] conj input)
        next-state  (apply-alternatives next-state (get-in next-state [:transitions input]))
        next-state  (or next-state machine)
        output-fs   (get-in next-state [:output-function (:state next-state)])
        next-state  (apply-alternatives next-state output-fs)]
    (let [deferred (seq (:internal-events next-state))]
      (if-not deferred
        next-state
        (recur (assoc-in next-state [:internal-events] (rest deferred)) (first deferred))))))
