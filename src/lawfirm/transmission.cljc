(ns lawfirm.transmission
  "送達 — a document leaving (or arriving at) the practice, recorded.

  ## Why the destination is a record, not a field

  The failure this namespace exists to make unrepresentable is **誤送信**: a
  書面 carrying a client's confidence arriving at the wrong fax machine. It is
  the most common confidentiality incident in Japanese practice and it has one
  structural cause — the destination is typed at the moment of sending, by the
  person in the biggest hurry, from a number on a piece of paper.

  So there is no destination field on a transmission. A transmission names a
  `recipient-id`, and a recipient is a registered record on the matter, with a
  named 弁護士 who verified each channel coordinate and the date they did it.
  A number nobody registered cannot be sent to, and a number verified two
  years ago is stale (`channel-verification-validity-days`) — fax numbers are
  reassigned, and the practice that finds out is the one that already sent.

  This is the same shape as `lawfirm.partner`'s counsel verification, for the
  same reason: an identity assertion with no date is an assertion about the
  past presented as a fact about now.

  ## What a sent fax proves

  Nothing about receipt. A 送信確認書 says a machine answered, not that the
  intended office holds the page — so `:result` records what the device
  reported and never advances the document's own state. 送達の証明 is a
  separate legal act (送達報告書, 配達証明) and if the practice has one it is a
  document in its own right, not a flag here.

  ## Inbound

  An arriving fax is the front door, and at the front door there is not yet a
  privilege to protect the text with (`lawfirm.intake`). Inbound rows carry a
  page count, an origin and a caller-supplied digest — never the prose."
  (:require [lawfirm.date :as date]
            [lawfirm.store :as store]))

(def channels
  "送達経路. `:hand` is 持参 — the one with no coordinate to verify, which is
  also why it is the only one that cannot be misdirected."
  #{:fax :post :email :hand :electronic-filing})

(def channel-labels
  {:fax "FAX" :post "郵便" :email "電子メール" :hand "持参"
   :electronic-filing "電子提出"})

(def channel-verification-validity-days
  "How long a verified channel coordinate stays current: 180 days, half the
  365 used for counsel registration.

  The two decay differently. A 弁護士's registration is a matter of public
  record that changes on announced events; a fax number or a postal address
  changes when an office moves and tells the people it remembers. The shorter
  window is not extra caution, it is the shorter half-life."
  180)

(defn coordinate
  "The registered coordinate for `channel` on `recipient`, or nil.
  `:hand` has none by construction and returns a marker rather than nil, so a
  caller cannot conclude 'unregistered' from a missing address."
  [recipient channel]
  (if (= :hand channel)
    {:in-person true}
    (get-in recipient [:channels channel])))

(defn verification-stale?
  "True when `channel`'s coordinate was verified longer ago than
  `channel-verification-validity-days`, or never."
  [recipient channel today]
  (if (= :hand channel)
    false
    (let [on (:verified-on (coordinate recipient channel))]
      (or (not (date/valid? on))
          (not (date/valid? today))
          (> (or (date/days-between on today) 0)
             channel-verification-validity-days)))))

;; ---------------------------------------------------------------------------
;; Channel restrictions — a drafting aid, not an authority
;; ---------------------------------------------------------------------------

(def channel-restrictions
  "書面種別 × 経路 の制約。

  **This table is a drafting aid and not an authority**, exactly like
  `lawfirm.deadline/statutory`. It lists only combinations this repository is
  confident are unavailable, with the provision to read. A combination that is
  *absent* from this table is **not thereby permitted** — it is
  `:requires-counsel-judgement`, which is the normal state of affairs and the
  reason 送達 is an escalated operation in the first place.

  Keyed by `[recipient-role channel]`; the value's `:kinds` are the document
  kinds the entry forbids."
  {[:court :fax]
   {:kinds #{:訴状 :控訴状 :上告状 :上告受理申立書 :上告理由書
             :上告受理申立て理由書}
    :cite "民事訴訟規則3条1項"
    :detail (str "訴訟手続を開始・完結させる書面と、同項が掲げるその他の除外書面は"
                 "ファクシミリで提出できない。各号の該当性判断は弁護士が行う。")}

   [:opposing-party :fax]
   {:kinds #{:内容証明}
    :cite "郵便法・内国郵便約款（内容証明は郵便役務）"
    :detail "内容証明は郵便役務であって、FAX では出せない（e内容証明は :electronic-filing）。"}

   [:opposing-counsel :fax]
   {:kinds #{:内容証明}
    :cite "郵便法・内国郵便約款（内容証明は郵便役務）"
    :detail "内容証明は郵便役務であって、FAX では出せない（e内容証明は :electronic-filing）。"}})

(defn restriction
  "The restriction this table asserts for a document going to a recipient by a
  channel, or nil. Returning nil means *this table has nothing to say* — see
  the docstring above for why that is not permission."
  [recipient channel doc-kind]
  (let [r (get channel-restrictions [(:role recipient) channel])]
    (when (contains? (:kinds r) doc-kind) r)))

;; ---------------------------------------------------------------------------
;; Governor input
;; ---------------------------------------------------------------------------

(defn outbound-violations
  "Every rule a proposed outbound 送達 breaks, as governor-shaped violation
  maps. An empty vector does not mean the transmission may happen: 送達 is
  always a 弁護士 decision (`lawfirm.governor` escalates it unconditionally)."
  [store {:keys [matter-id doc-id recipient-id channel] :as _transmission} today]
  (let [r (store/recipient store recipient-id)
        wp (when doc-id (store/work-product store doc-id))
        res (when r (restriction r channel (:kind wp)))]
    (cond-> []
      (not (contains? channels channel))
      (conj {:rule :transmission-channel-unknown
             :detail (str "未定義の送達経路 " (pr-str channel)
                          "（" (->> channels (map name) sort (interpose "・") (apply str)) " のいずれか）")})

      (nil? r)
      (conj {:rule :recipient-not-registered
             :detail (str "送達先 " (pr-str recipient-id)
                          " が事件に登録されていない。宛先は送信時に入力するものでは"
                          "なく、事前に登録し弁護士が確認したものから選ぶ（誤送信の防止）")})

      (and r (not= matter-id (:matter-id r)))
      (conj {:rule :recipient-wrong-matter
             :detail (str "送達先 " recipient-id " は事件 " (:matter-id r) " のもの")})

      (and r (not= :hand channel) (nil? (coordinate r channel)))
      (conj {:rule :recipient-channel-unregistered
             :detail (str "送達先 " recipient-id " に "
                          (get channel-labels channel (name channel))
                          " の宛先が登録されていない")})

      (and r (coordinate r channel) (verification-stale? r channel today))
      (conj {:rule :recipient-channel-unverified
             :detail (str (get channel-labels channel (name channel))
                          "の宛先の確認記録が無いか " channel-verification-validity-days
                          "日を超えて古い（確認日 "
                          (or (:verified-on (coordinate r channel)) "未記録")
                          "）。宛先は移転する — 古い確認は現在についての事実ではない")})

      (nil? wp)
      (conj {:rule :unknown-work-product
             :detail "未登録の書面は送達できない"})

      (and wp (not= :issued (:status wp)))
      (conj {:rule :work-product-not-issued
             :detail (str "書面 " doc-id " は status " (pr-str (:status wp))
                          "。発出が決まっていない書面を「送った」ことにはできない"
                          "（発出は :issue-work-product、送達はその後の別の行為）")})

      (some? res)
      (conj {:rule :channel-forbidden-for-document
             :detail (str "書面種別 " (pr-str (:kind wp)) " を "
                          (get channel-labels channel (name channel))
                          " で送達できない — " (:detail res) "（" (:cite res) "）")
             :cite (:cite res)}))))

;; ---------------------------------------------------------------------------
;; Inbound
;; ---------------------------------------------------------------------------

(defn inbound-record
  "The persistable record of something that arrived. Carries no prose, for the
  same reason `lawfirm.intake/->consult-record` does not: at the moment a fax
  lands there may be no attorney-client relationship yet, and a record layer
  that holds the text is a record layer that leaks it.

  `digest` is whatever identifier the caller can later use to find the
  original in a store this namespace does not reach."
  [{:keys [transmission-id matter-id channel received-on origin page-count digest]}]
  {:transmission-id transmission-id
   :matter-id matter-id
   :direction :inbound
   :channel channel
   :sent-on received-on
   :origin origin
   :page-count page-count
   :digest digest})

(defn ->consult-channel
  "The `:channel` an inbound transmission contributes to
  `lawfirm.intake/->consult-record`, so a fax that turns out to be a new
  enquiry enters triage as a fax rather than as an untyped event."
  [t]
  (:channel t))

;; ---------------------------------------------------------------------------
;; Derived reads — the same functions the console and the gate share
;; ---------------------------------------------------------------------------

(defn outbound-of [store matter-id]
  (filterv #(= :outbound (:direction %)) (store/transmissions-of store matter-id)))

(defn inbound-of [store matter-id]
  (filterv #(= :inbound (:direction %)) (store/transmissions-of store matter-id)))

(defn channel-mix
  "件数 by channel across a matter — the 分析 a practice actually acts on,
  because a matter whose 送達 is all fax is a matter with a single point of
  failure."
  [store matter-id]
  (frequencies (map :channel (store/transmissions-of store matter-id))))

(defn unconfirmed
  "Outbound transmissions whose device result was never recorded. Not a legal
  category — a practice cannot chase what it cannot list."
  [store matter-id]
  (filterv #(nil? (:result %)) (outbound-of store matter-id)))

(defn stale-channels
  "Registered recipient channels whose verification has lapsed, across a
  matter. Surfaced so the practice fixes them before a 送達 is held rather
  than at the moment it is trying to send."
  [store matter-id today]
  (vec
   (for [r (store/recipients-of store matter-id)
         ch (keys (:channels r))
         :when (verification-stale? r ch today)]
     {:recipient-id (:recipient-id r)
      :name (:name r)
      :channel ch
      :verified-on (:verified-on (coordinate r ch))})))
