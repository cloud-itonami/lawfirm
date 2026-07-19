(ns lg-lawfirm-intake.graph
  "Intake matching StateGraph — faithful clj port of
  `lg/lg_lawfirm_intake/graph.py` (ADR-2606280030).

  Topology (identical to the Python): START → triage → summarize → search → match → END.

  The Python `IntakeState` TypedDict becomes a plain clj map (keyword keys
  mirroring the Python field names). Each node returns a partial state map that
  the StateGraph runtime merges — same contract as the Python dict updates.

  DEVIATION (noted): langgraph-clj has no `RetryPolicy` / async; nodes run
  synchronously."
  (:require [langgraph.graph :as g]
            [lg-lawfirm-intake.nodes :as nodes]))

(defn build
  "Compile the lawfirm_intake StateGraph (triage → summarize → search → match)."
  []
  (-> (g/state-graph)
      (g/add-node :triage    nodes/triage-node)
      (g/add-node :summarize nodes/summarize-node)
      (g/add-node :search    nodes/search-node)
      (g/add-node :match     nodes/match-node)
      (g/add-edge :triage :summarize)
      (g/add-edge :summarize :search)
      (g/add-edge :search :match)
      (g/set-entry-point :triage)
      (g/set-finish-point :match)
      (g/compile-graph)))

(def GRAPH (build))
