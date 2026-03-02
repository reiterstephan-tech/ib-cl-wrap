# ib-cl-wrap

English README: [README.en.md](/Users/stephan/Syncthing/dev/clojure/ib-cl-wrap/README.en.md)

Asynchroner Clojure-Wrapper fuer die Interactive Brokers TWS/IB Gateway Java API.

Der Wrapper nutzt `core.async` fuer ein Event-basiertes Modell und vermeidet blockierende Logik in IB-Callback-Threads.

## Spec-driven

Das Projekt ist spec-driven aufgebaut:
- zentrale Specs: [spec.clj](/Users/stephan/Syncthing/dev/clojure/ib-cl-wrap/src/ib/spec.clj)
- Public Surface Uebersicht: [spec-surface.md](/Users/stephan/Syncthing/dev/clojure/ib-cl-wrap/docs/spec-surface.md)
- Event-Vertrag: [events.md](/Users/stephan/Syncthing/dev/clojure/ib-cl-wrap/docs/events.md)

Garantiert werden:
- strukturierte Config-Maps fuer Public APIs
- strukturierte Event-Maps pro `:type`
- strukturierte Snapshot-Result-Maps

## Setup

1. `ibapi.jar` in `lib/ibapi.jar` ablegen.
2. TWS oder IB Gateway starten.
3. In TWS/Gateway API aktivieren und Socket-Port oeffnen.

Typische Ports:
- TWS Paper: `7497`
- TWS Live: `7496`
- IB Gateway Paper: `4002`
- IB Gateway Live: `4001`

In `deps.edn` ist `lib/ibapi.jar` bereits in `:paths` eingetragen. Wenn die JAR fehlt, werfen `ib.client/connect!` und andere IB-nahe Funktionen eine erklaerende Exception.

## API

### Namespace `ib.client`

- `connect!` - verbindet zu TWS/Gateway und startet den `EReader`-Loop (ohne busy-wait).
- `disconnect!` - trennt Verbindung und schliesst Event-Ressourcen.
- `subscribe-events!` - liefert Subscriber-Channel fuer Events.
- `unsubscribe-events!` - entfernt Subscriber-Channel.
- `req-positions!` - triggert `reqPositions()`.
- `req-account-summary!` - triggert `reqAccountSummary(reqId, group, tags)`.
- `cancel-account-summary!` - triggert `cancelAccountSummary(reqId)`.
- `req-account-updates!` - triggert `reqAccountUpdates(true, account)` fuer Streaming aus dem TWS Account Window.
- `cancel-account-updates!` - triggert `reqAccountUpdates(false, account)` zum Beenden des Streams.
- `req-open-orders!` - triggert `reqOpenOrders()` (offene Orders des aktuellen Client IDs; mit ClientId 0 koennen manuelle TWS-Orders gebunden sein).
- `req-all-open-orders!` - triggert `reqAllOpenOrders()` (alle offenen API-Orders unabhaengig vom Client ID).
- `register-request!` / `unregister-request!` / `request-context` - Request-Korrelation ueber `req-id` fuer request-bezogene Fehler.
- `dropped-event-count` - Anzahl nicht enqueueter Events.
- `events-chan` - gibt den geteilten Event-Channel zurueck.

### Namespace `ib.positions`

- `positions-snapshot!` - triggert `reqPositions()`, sammelt `:ib/position` bis `:ib/position-end`, liefert Ergebnis auf Channel.
- `positions-snapshot-from-events!` - reine Collector-Funktion fuer simulierte Tests.

### Namespace `ib.account`

- `account-summary-snapshot!` - triggert `reqAccountSummary`, sammelt `:ib/account-summary` bis `:ib/account-summary-end` (fuer eine `req-id`) und cancelt die Subscription immer aktiv.
- `account-summary-snapshot-from-events!` - Collector fuer simulierte Tests.
- `next-req-id!` - erzeugt `req-id` fuer Snapshot-Anfragen.

### Namespace `ib.open-orders`

- `open-orders-snapshot!` - Snapshot fuer Open Orders mit `:mode :open` (`reqOpenOrders`) oder `:mode :all` (`reqAllOpenOrders`).
- `open-orders-snapshot-from-events!` - Collector fuer simulierte Tests.
- Guard gegen parallele Open-Orders-Snapshots pro Verbindung (`:snapshot-in-flight` Fehler).

Default-Tags fuer Balances:
- `NetLiquidation`
- `TotalCashValue`
- `AvailableFunds`
- `BuyingPower`
- `UnrealizedPnL`
- `RealizedPnL`

### Namespace `ib.events`

- `contract->map` - normalisiert Contract auf stabiles Map-Format.
- `position->event`, `error->event` - Event-Normalisierung.
- `create-event-bus` - bounded Event-Infrastruktur mit Backpressure-Strategie.

## Event-Schema

Minimale Event-Typen:
- `{:type :ib/connected ...}`
- `{:type :ib/error ...}`
- `{:type :ib/position ...}`
- `{:type :ib/position-end ...}`
- `{:type :ib/account-summary :req-id <int> :account <string> :tag <string> :value <string> :currency <string> :ts <millis>}`
- `{:type :ib/account-summary-end :req-id <int> :ts <millis>}`
- `{:type :ib/open-order :order-id <int> :contract {...} :order {...} :order-state {...} :ts <millis>}`
- `{:type :ib/order-status :order-id <int> :status-text <string> :filled <double> :remaining <double> ...}`
- `{:type :ib/open-order-end :ts <millis>}`
- `{:type :ib/update-account-value :key <string> :value <string> :currency <string> :account <string> :ts <millis>}`
- `{:type :ib/update-account-time :time <string> :ts <millis>}`
- `{:type :ib/update-portfolio :contract {...} :position <double> ... :account <string> :ts <millis>}`
- `{:type :ib/account-download-end :account <string> :ts <millis>}`

Einheitliche Event-Felder (v1):
- `:type`
- `:source`
- `:status`
- `:request-id`
- `:ts`
- `:schema-version`

IB-Fehler (`:ib/error`) enthalten bei korrelierbaren Requests zusaetzlich:
- `:request-id`
- `:request` (z. B. `{:type :account-summary ...}`)
- `:retryable?` (heuristische Klassifikation fuer transient/retrybar)

Versionierter Event-Contract: [docs/event-schema-v1.md](/Users/stephan/Syncthing/dev/clojure/ib-cl-wrap/docs/event-schema-v1.md)

Contract-Normalisierung (stabil):
- `:conId`
- `:symbol`
- `:secType`
- `:currency`
- `:exchange`

Account Summary ist in IB eine Subscription. Der Snapshot-Helper beendet sie aktiv mit `cancelAccountSummary` bei Erfolg und bei Timeout/Fehler.

Account Updates (`reqAccountUpdates`) ist ebenfalls subscription-basiert und liefert Account-/Portfolio-Daten wie im TWS Account Window.

## Overflow-Strategie

Default: `:sliding` auf bounded Channels.

Das bedeutet: Bei vollem Puffer werden aeltere Events verworfen, neuere behalten. Callback-Threads blockieren dadurch nicht.

## Beispiel

```clojure
(require '[clojure.core.async :as async]
         '[ib.client :as ib]
         '[ib.account :as acct]
         '[ib.open-orders :as oo]
         '[ib.positions :as pos])

(def conn
  (ib/connect! {:host "127.0.0.1"
                :port 7497
                :client-id 42
                :event-buffer-size 2048
                :overflow-strategy :sliding}))

(def events-ch (ib/subscribe-events! conn {:buffer-size 512}))

(async/go-loop []
  (when-let [evt (async/<! events-ch)]
    (println "EVENT" evt)
    (recur)))

(def snapshot-ch (pos/positions-snapshot! conn {:timeout-ms 5000}))

(def balance-ch
  (acct/account-summary-snapshot!
   conn
   {:group "All"
    :tags ["NetLiquidation" "TotalCashValue" "AvailableFunds" "BuyingPower" "UnrealizedPnL" "RealizedPnL"]
    :timeout-ms 5000}))

(def open-orders-ch
  (oo/open-orders-snapshot! conn {:mode :open :timeout-ms 5000}))

(async/go
  (let [result (async/<! snapshot-ch)]
    (if (= :ib/error (:type result))
      (println "Positions-Fehler:" result)
      (println "Positions-Snapshot:" result))))

(async/go
  (let [result (async/<! balance-ch)]
    (if (:ok result)
      (println "Balances:" (:values result))
      (println "Account-Summary-Fehler:" result))))

(async/go
  (let [result (async/<! open-orders-ch)]
    (if (:ok result)
      (println "Open Orders:" (:orders result))
      (println "Open-Orders-Fehler:" result))))

;; Optional: Streaming-Updates fuer ein Konto
(ib/req-account-updates! conn {:account "DU123456"})

;; laufende Events werden ueber events-ch geliefert:
;; :ib/update-account-value
;; :ib/update-account-time
;; :ib/update-portfolio
;; :ib/account-download-end

;; spaeter Stream beenden
(ib/cancel-account-updates! conn "DU123456")

;; spaeter
(ib/unsubscribe-events! conn events-ch)
(ib/disconnect! conn)
```

## Tests

Tests laufen ohne echte IB-Verbindung (simulierte Events):

```bash
clj -M:test
```

Das Testprofil aktiviert `clojure.spec.test.alpha/instrument` fuer die Public API-Funktionen.
In Produktion wird Instrumentation nicht automatisch aktiviert.

Getestet wird:
- Contract-Normalisierung
- Snapshot-Collector-Logik
- Timeout-Verhalten
- Account-Summary Event-Normalisierung und Snapshot-Timeout inkl. Cancel
- Account-Updates API (`reqAccountUpdates`) und Event-Normalisierung
- Open-Orders Snapshot-Collector (inkl. Timeout und In-Flight-Guard)
- Spec-Instrumentation und generative Spec-Checks

## Hinweise Paper vs Live

- Paper und Live nutzen unterschiedliche Ports/Accounts.
- Verwende unterschiedliche `client-id` pro Client-Verbindung.
- Erst in Paper testen, dann Live aktivieren.
