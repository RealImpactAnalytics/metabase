(ns metabase.pulse.render
  (:require [clj-time
             [coerce :as c]
             [core :as t]
             [format :as f]]
            [clojure
             [pprint :refer [cl-format]]
             [string :as str]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [hiccup.core :refer [h html]]
            [metabase.util :as u]
            [metabase.util.urls :as urls]
            [puppetlabs.i18n.core :refer [tru trs]]
            [schema.core :as s])
  (:import cz.vutbr.web.css.MediaSpec
           [java.awt BasicStroke Color Dimension RenderingHints]
           java.awt.image.BufferedImage
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           java.net.URL
           java.nio.charset.StandardCharsets
           [java.util Arrays Date]
           javax.imageio.ImageIO
           org.apache.commons.io.IOUtils
           [org.fit.cssbox.css CSSNorm DOMAnalyzer DOMAnalyzer$Origin]
           [org.fit.cssbox.io DefaultDOMSource StreamDocumentSource]
           org.fit.cssbox.layout.BrowserCanvas
           org.fit.cssbox.misc.Base64Coder
           org.joda.time.DateTimeZone))

;; NOTE: hiccup does not escape content by default so be sure to use "h" to escape any user-controlled content :-/

;;; # ------------------------------------------------------------ STYLES ------------------------------------------------------------

(def ^:private ^:const card-width 400)
(def ^:private ^:const rows-limit 10)
(def ^:private ^:const cols-limit 3)
(def ^:private ^:const sparkline-dot-radius 6)
(def ^:private ^:const sparkline-thickness 3)
(def ^:private ^:const sparkline-pad 8)

;;; ## STYLES
(def ^:private ^:const color-brand  "rgb(45,134,212)")
(def ^:private ^:const color-purple "rgb(135,93,175)")
(def ^:private ^:const color-gray-1 "rgb(248,248,248)")
(def ^:private ^:const color-gray-2 "rgb(189,193,191)")
(def ^:private ^:const color-gray-3 "rgb(124,131,129)")
(def ^:const color-gray-4 "A ~25% Gray color." "rgb(57,67,64)")

(def ^:private ^:const font-style    {:font-family "Lato, \"Helvetica Neue\", Helvetica, Arial, sans-serif"})
(def ^:const section-style
  "CSS style for a Pulse section."
  font-style)

(def ^:private ^:const header-style
  (merge font-style {:font-size       :16px
                     :font-weight     700
                     :color           color-gray-4
                     :text-decoration :none}))

(def ^:private ^:const scalar-style
  (merge font-style {:font-size   :24px
                     :font-weight 700
                     :color       color-brand}))

(def ^:private ^:const bar-th-style
  (merge font-style {:font-size      :10px
                     :font-weight    400
                     :color          color-gray-4
                     :border-bottom  (str "4px solid " color-gray-1)
                     :padding-top    :0px
                     :padding-bottom :10px}))

(def ^:private ^:const bar-td-style
  (merge font-style {:font-size     :16px
                     :font-weight   400
                     :text-align    :left
                     :padding-right :1em
                     :padding-top   :8px}))

(def ^:private RenderedPulseCard
  "Schema used for functions that operate on pulse card contents and their attachments"
  {:attachments (s/maybe {s/Str URL})
   :content [s/Any]})

;;; # ------------------------------------------------------------ HELPER FNS ------------------------------------------------------------

(defn- style
  "Compile one or more CSS style maps into a string.

     (style {:font-weight 400, :color \"white\"}) -> \"font-weight: 400; color: white;\""
  [& style-maps]
  (str/join " " (for [[k v] (into {} style-maps)
                      :let  [v (if (keyword? v) (name v) v)]]
                  (str (name k) ": " v ";"))))


(defn- datetime-field?
  [field]
  (or (isa? (:base_type field)    :type/DateTime)
      (isa? (:special_type field) :type/DateTime)))

(defn- number-field?
  [field]
  (or (isa? (:base_type field)    :type/Number)
      (isa? (:special_type field) :type/Number)))


;;; # ------------------------------------------------------------ FORMATTING ------------------------------------------------------------

(defn- format-number
  [n]
  (cl-format nil (if (integer? n) "~:d" "~,2f") n))

(defn- reformat-timestamp [timezone old-format-timestamp new-format-string]
  (f/unparse (f/with-zone (f/formatter new-format-string)
               (DateTimeZone/forTimeZone timezone))
             (u/str->date-time old-format-timestamp timezone)))

(defn- format-timestamp
  "Formats timestamps with human friendly absolute dates based on the column :unit"
  [timezone timestamp col]
  (case (:unit col)
    :hour          (reformat-timestamp timezone timestamp "h a - MMM YYYY")
    :week          (str "Week " (reformat-timestamp timezone timestamp "w - YYYY"))
    :month         (reformat-timestamp timezone timestamp "MMMM YYYY")
    :quarter       (let [timestamp-obj (u/str->date-time timestamp timezone)]
                     (str "Q"
                          (inc (int (/ (t/month timestamp-obj)
                                       3)))
                          " - "
                          (t/year timestamp-obj)))

    (:year :hour-of-day :day-of-week :week-of-year :month-of-year); TODO: probably shouldn't even be showing sparkline for x-of-y groupings?
    (str timestamp)

    (reformat-timestamp timezone timestamp "MMM d, YYYY")))

(def ^:private year  (comp t/year  t/now))
(def ^:private month (comp t/month t/now))
(def ^:private day   (comp t/day   t/now))

(defn- date->interval-name [date interval-start interval this-interval-name last-interval-name]
  (cond
    (t/within? (t/interval interval-start                    (t/plus interval-start interval)) date) this-interval-name
    (t/within? (t/interval (t/minus interval-start interval) interval-start)                   date) last-interval-name))

(defn- start-of-this-week    [] (-> (org.joda.time.LocalDate.) .weekOfWeekyear .roundFloorCopy .toDateTimeAtStartOfDay))
(defn- start-of-this-quarter [] (t/date-midnight (year) (inc (* 3 (Math/floor (/ (dec (month))
                                                                                 3))))))

(defn- format-timestamp-relative
  "Formats timestamps with relative names (today, yesterday, this *, last *) based on column :unit, if possible, otherwie returns nil"
  [timezone timestamp, {:keys [unit]}]
  (let [parsed-timestamp (u/str->date-time timestamp timezone)]
    (case unit
      :day     (date->interval-name parsed-timestamp
                                    (t/date-midnight (year) (month) (day))
                                    (t/days 1) "Today" "Yesterday")
      :week    (date->interval-name parsed-timestamp
                                    (start-of-this-week)
                                    (t/weeks 1) "This week" "Last week")
      :month   (date->interval-name parsed-timestamp
                                    (t/date-midnight (year) (month))
                                    (t/months 1) "This month" "Last month")
      :quarter (date->interval-name parsed-timestamp
                                    (start-of-this-quarter)
                                    (t/months 3) "This quarter" "Last quarter")
      :year    (date->interval-name (t/date-midnight parsed-timestamp)
                                    (t/date-midnight (year))
                                    (t/years 1) "This year" "Last year")
      nil)))

(defn- format-timestamp-pair
  "Formats a pair of timestamps, using relative formatting for the first timestamps if possible and 'Previous :unit' for the second, otherwise absolute timestamps for both"
  [timezone [a b] col]
  (if-let [a' (format-timestamp-relative timezone a col)]
    [a' (str "Previous " (-> col :unit name))]
    [(format-timestamp timezone a col) (format-timestamp timezone b col)]))

(defn- format-cell
  [timezone value col]
  (cond
    (datetime-field? col) (format-timestamp timezone value col)
    (and (number? value) (not (datetime-field? col))) (format-number value)
    :else (str value)))

(defn- render-img-data-uri
  "Takes a PNG byte array and returns a Base64 encoded URI"
  [img-bytes]
  (str "data:image/png;base64," (String. (Base64Coder/encode img-bytes))))

;;; # ------------------------------------------------------------ RENDERING ------------------------------------------------------------

(def ^:dynamic *include-buttons*
  "Should the rendered pulse include buttons? (default: `false`)"
  false)

(def ^:dynamic *include-title*
  "Should the rendered pulse include a title? (default: `false`)"
  false)

(def ^:dynamic *render-img-fn*
  "The function that should be used for rendering image bytes. Defaults to `render-img-data-uri`."
  render-img-data-uri)

(defn- card-href
  [card]
  (h (urls/card-url (:id card))))

;; ported from https://github.com/radkovo/CSSBox/blob/cssbox-4.10/src/main/java/org/fit/cssbox/demo/ImageRenderer.java
(defn- render-to-png
  [^String html, ^ByteArrayOutputStream os, width]
  (let [is            (ByteArrayInputStream. (.getBytes html StandardCharsets/UTF_8))
        doc-source    (StreamDocumentSource. is nil "text/html; charset=utf-8")
        parser        (DefaultDOMSource. doc-source)
        doc           (.parse parser)
        window-size   (Dimension. width 1)
        media         (doto (MediaSpec. "screen")
                        (.setDimensions       (.width window-size) (.height window-size))
                        (.setDeviceDimensions (.width window-size) (.height window-size)))
        da            (doto (DOMAnalyzer. doc (.getURL doc-source))
                        (.setMediaSpec media)
                        .attributesToStyles
                        (.addStyleSheet nil (CSSNorm/stdStyleSheet)   DOMAnalyzer$Origin/AGENT)
                        (.addStyleSheet nil (CSSNorm/userStyleSheet)  DOMAnalyzer$Origin/AGENT)
                        (.addStyleSheet nil (CSSNorm/formsStyleSheet) DOMAnalyzer$Origin/AGENT)
                        .getStyleSheets)
        content-canvas (doto (BrowserCanvas. (.getRoot da) da (.getURL doc-source))
                         (.setAutoMediaUpdate false)
                         (.setAutoSizeUpdate true))]
    (doto (.getConfig content-canvas)
      (.setClipViewport false)
      (.setLoadImages true)
      (.setLoadBackgroundImages true))
    (.createLayout content-canvas window-size)
    (ImageIO/write (.getImage content-canvas) "png" os)))

(s/defn ^:private render-html-to-png :- bytes
  [{:keys [content]} :- RenderedPulseCard
   width]
  (let [html (html [:html [:body {:style (style {:margin           0
                                                 :padding          0
                                                 :background-color :white})}
                           content]])
        os   (ByteArrayOutputStream.)]
    (render-to-png html os width)
    (.toByteArray os)))

(defn- render-table
  [header+rows]
  [:table {:style (style {:padding-bottom :8px, :border-bottom (str "4px solid " color-gray-1)})}
   (let [{header-row :row bar-width :bar-width} (first header+rows)]
     [:thead
      [:tr
       (for [header-cell header-row]
         [:th {:style (style bar-td-style bar-th-style {:min-width :60px})}
          (h header-cell)])
       (when bar-width
         [:th {:style (style bar-td-style bar-th-style {:width (str bar-width "%")})}])]])
   [:tbody
    (map-indexed (fn [row-idx {:keys [row bar-width]}]
                   [:tr {:style (style {:color (if (odd? row-idx) color-gray-2 color-gray-3)})}
                    (map-indexed (fn [col-idx cell]
                                   [:td {:style (style bar-td-style (when (and bar-width (= col-idx 1)) {:font-weight 700}))}
                                    (h cell)])
                                 row)
                    (when bar-width
                      [:td {:style (style bar-td-style {:width :99%})}
                       [:div {:style (style {:background-color color-purple
                                             :max-height       :10px
                                             :height           :10px
                                             :border-radius    :2px
                                             :width            (str bar-width "%")})}
                        "&#160;"]])])
                 (rest header+rows))]])

(defn- create-remapping-lookup
  "Creates a map with from column names to a column index. This is
  used to figure out what a given column name or value should be
  replaced with"
  [cols]
  (into {}
        (for [[col-idx {:keys [remapped_from]}] (map vector (range) cols)
              :when remapped_from]
          [remapped_from col-idx])))

(defn- query-results->header-row
  "Returns a row structure with header info from `COLS`. These values
  are strings that are ready to be rendered as HTML"
  [remapping-lookup cols include-bar?]
  {:row (for [maybe-remapped-col cols
              :let [col (if (:remapped_to maybe-remapped-col)
                          (nth cols (get remapping-lookup (:name maybe-remapped-col)))
                          maybe-remapped-col)]
              ;; If this column is remapped from another, it's already
              ;; in the output and should be skipped
              :when (not (:remapped_from maybe-remapped-col))]
          (str/upper-case (name (or (:display_name col) (:name col)))))
   :bar-width (when include-bar? 99)})

(defn- query-results->row-seq
  "Returns a seq of stringified formatted rows that can be rendered into HTML"
  [timezone remapping-lookup cols rows bar-column max-value]
  (for [row rows]
    {:bar-width (when bar-column
                  ;; cast to double to avoid "Non-terminating decimal expansion" errors
                  (float (* 100 (/ (double (bar-column row)) max-value))))
     :row (for [[maybe-remapped-col maybe-remapped-row-cell] (map vector cols row)
                :when (not (:remapped_from maybe-remapped-col))
                :let [[col row-cell] (if (:remapped_to maybe-remapped-col)
                                       [(nth cols (get remapping-lookup (:name maybe-remapped-col)))
                                        (nth row (get remapping-lookup (:name maybe-remapped-col)))]
                                       [maybe-remapped-col maybe-remapped-row-cell])]]
            (format-cell timezone row-cell col))}))

(defn- prep-for-html-rendering
  "Convert the query results (`COLS` and `ROWS`) into a formatted seq
  of rows (list of strings) that can be rendered as HTML"
  [timezone cols rows bar-column max-value column-limit]
  (let [remapping-lookup (create-remapping-lookup cols)
        limited-cols (take column-limit cols)]
    (cons
     (query-results->header-row remapping-lookup limited-cols bar-column)
     (query-results->row-seq timezone remapping-lookup limited-cols (take rows-limit rows) bar-column max-value))))

(defn- render-truncation-warning
  [col-limit col-count row-limit row-count]
  (if (or (> row-count row-limit)
          (> col-count col-limit))
    [:div {:style (style {:padding-top :16px})}
     (cond
       (> row-count row-limit)
       [:div {:style (style {:color color-gray-2
                             :padding-bottom :10px})}
        "Showing " [:strong {:style (style {:color color-gray-3})} (format-number row-limit)]
        " of "     [:strong {:style (style {:color color-gray-3})} (format-number row-count)]
        " rows."]

       (> col-count col-limit)
       [:div {:style (style {:color          color-gray-2
                             :padding-bottom :10px})}
        "Showing " [:strong {:style (style {:color color-gray-3})} (format-number col-limit)]
        " of "     [:strong {:style (style {:color color-gray-3})} (format-number col-count)]
        " columns."])]))

(s/defn ^:private render:table :- RenderedPulseCard
  [timezone card {:keys [cols rows] :as data}]
  {:attachments nil
   :content     [:div
                 (render-table (prep-for-html-rendering timezone cols rows nil nil cols-limit))
                 (render-truncation-warning cols-limit (count cols) rows-limit (count rows))]})

(s/defn ^:private render:bar :- RenderedPulseCard
  [timezone card {:keys [cols rows] :as data}]
  (let [max-value (apply max (map second rows))]
    {:attachments nil
     :content     [:div
                   (render-table (prep-for-html-rendering timezone cols rows second max-value 2))
                   (render-truncation-warning 2 (count cols) rows-limit (count rows))]}))

(s/defn ^:private render:scalar :- RenderedPulseCard
  [timezone card {:keys [cols rows]}]
  {:attachments nil
   :content     [:div {:style (style scalar-style)}
                 (h (format-cell timezone (ffirst rows) (first cols)))]})

(defn- render-sparkline-to-png
  "Takes two arrays of numbers between 0 and 1 and plots them as a sparkline"
  [xs ys width height]
  (let [os    (ByteArrayOutputStream.)
        image (BufferedImage. (+ width (* 2 sparkline-pad)) (+ height (* 2 sparkline-pad)) BufferedImage/TYPE_INT_ARGB)
        xt    (map #(+ sparkline-pad (* width %)) xs)
        yt    (map #(+ sparkline-pad (- height (* height %))) ys)]
    (doto (.createGraphics image)
      (.setRenderingHints (RenderingHints. RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON))
      (.setColor (Color. 211 227 241))
      (.setStroke (BasicStroke. sparkline-thickness BasicStroke/CAP_ROUND BasicStroke/JOIN_ROUND))
      (.drawPolyline (int-array (count xt) xt)
                     (int-array (count yt) yt)
                     (count xt))
      (.setColor (Color. 45 134 212))
      (.fillOval (- (last xt) sparkline-dot-radius)
                 (- (last yt) sparkline-dot-radius)
                 (* 2 sparkline-dot-radius)
                 (* 2 sparkline-dot-radius))
      (.setColor Color/white)
      (.setStroke (BasicStroke. 2))
      (.drawOval (- (last xt) sparkline-dot-radius)
                 (- (last yt) sparkline-dot-radius)
                 (* 2 sparkline-dot-radius)
                 (* 2 sparkline-dot-radius)))
    (when-not (ImageIO/write image "png" os)                    ; returns `true` if successful -- see JavaDoc
      (let [^String msg (tru "No approprate image writer found!")]
        (throw (Exception. msg))))
    (.toByteArray os)))

(defn- hash-bytes
  "Generate a hash to be used in a Content-ID"
  [^bytes img-bytes]
  (Math/abs ^Integer (Arrays/hashCode img-bytes)))

(defn- hash-image-url
  "Generate a hash to be used in a Content-ID"
  [^java.net.URL url]
  (-> url io/input-stream IOUtils/toByteArray hash-bytes))

(defn- content-id-reference [content-id]
  (str "cid:" content-id))

(defn- mb-hash-str [image-hash]
  (str image-hash "@metabase"))

(defn- write-byte-array-to-temp-file
  [^bytes img-bytes]
  (let [f (doto (java.io.File/createTempFile "metabase_pulse_image_" ".png")
            .deleteOnExit)]
    (with-open [fos (java.io.FileOutputStream. f)]
      (.write fos img-bytes))
    f))

(defn- byte-array->url [^bytes img-bytes]
  (-> img-bytes write-byte-array-to-temp-file io/as-url))

(defmulti ^:private make-image-bundle
  "Create an image bundle. An image bundle contains the data needed to either encode the image inline (when
  `RENDER-TYPE` is `:inline`), or create the hashes/references needed for an attached image (`RENDER-TYPE` of
  `:attachment`)"
  (fn [render-type url-or-bytes]
    [render-type (class url-or-bytes)]))

(defmethod make-image-bundle [:attachment java.net.URL]
  [render-type ^java.net.URL url]
  (let [content-id (mb-hash-str (hash-image-url url))]
    {:content-id  content-id
     :image-url   url
     :image-src   (content-id-reference content-id)
     :render-type render-type}))

(defmethod make-image-bundle [:attachment (class (byte-array 0))]
  [render-type image-bytes]
  (let [image-url (byte-array->url image-bytes)
        content-id (mb-hash-str (hash-bytes image-bytes))]
    {:content-id  content-id
     :image-url   image-url
     :image-src   (content-id-reference content-id)
     :render-type render-type}))

(defmethod make-image-bundle [:inline java.net.URL]
  [render-type ^java.net.URL url]
  {:image-src   (-> url io/input-stream IOUtils/toByteArray render-img-data-uri)
   :image-url   url
   :render-type render-type})

(defmethod make-image-bundle [:inline (class (byte-array 0))]
  [render-type image-bytes]
  {:image-src   (render-img-data-uri image-bytes)
   :render-type render-type})

(def ^:private external-link-url (io/resource "frontend_client/app/assets/img/external_link.png"))
(def ^:private no-results-url    (io/resource "frontend_client/app/assets/img/pulse_no_results@2x.png"))

(def ^:private external-link-image
  (delay
   (make-image-bundle :attachment external-link-url)))

(def ^:private no-results-image
  (delay
   (make-image-bundle :attachment no-results-url)))

(defn- external-link-image-bundle [render-type]
  (case render-type
    :attachment @external-link-image
    :inline (make-image-bundle render-type external-link-url)))

(defn- no-results-image-bundle [render-type]
  (case render-type
    :attachment @no-results-image
    :inline (make-image-bundle render-type no-results-url)))

(defn- image-bundle->attachment [{:keys [render-type content-id image-url]}]
  (case render-type
    :attachment {content-id image-url}
    :inline     nil))

(s/defn ^:private render:sparkline :- RenderedPulseCard
  [render-type timezone card {:keys [rows cols]}]
  (let [ft-row (if (datetime-field? (first cols))
                 #(.getTime ^Date (u/->Timestamp %))
                 identity)
        rows   (if (> (ft-row (ffirst rows))
                      (ft-row (first (last rows))))
                 (reverse rows)
                 rows)
        xs     (for [row  rows
                     :let [x (first row)]]
                 (ft-row x))
        xmin   (apply min xs)
        xmax   (apply max xs)
        xrange (- xmax xmin)
        xs'    (map #(/ (double (- % xmin)) xrange) xs)
        ys     (map second rows)
        ymin   (apply min ys)
        ymax   (apply max ys)
        yrange (max 1 (- ymax ymin))                    ; `(max 1 ...)` so we don't divide by zero
        ys'    (map #(/ (double (- % ymin)) yrange) ys) ; cast to double to avoid "Non-terminating decimal expansion" errors
        rows'  (reverse (take-last 2 rows))
        values (map (comp format-number second) rows')
        labels (format-timestamp-pair timezone (map first rows') (first cols))
        image-bundle (make-image-bundle render-type (render-sparkline-to-png xs' ys' 524 130))]

    {:attachments (when image-bundle
                    (image-bundle->attachment image-bundle))
     :content     [:div
                   [:img {:style (style {:display :block
                                         :width :100%})
                          :src   (:image-src image-bundle)}]
                   [:table
                    [:tr
                     [:td {:style (style {:color         color-brand
                                          :font-size     :24px
                                          :font-weight   700
                                          :padding-right :16px})}
                      (first values)]
                     [:td {:style (style {:color       color-gray-3
                                          :font-size   :24px
                                          :font-weight 700})}
                      (second values)]]
                    [:tr
                     [:td {:style (style {:color         color-brand
                                          :font-size     :16px
                                          :font-weight   700
                                          :padding-right :16px})}
                      (first labels)]
                     [:td {:style (style {:color     color-gray-3
                                          :font-size :16px})}
                      (second labels)]]]]}))

(s/defn ^:private render:empty :- RenderedPulseCard
  [render-type _ _]
  (let [image-bundle (no-results-image-bundle render-type)]
    {:attachments (image-bundle->attachment image-bundle)
     :content     [:div {:style (style {:text-align :center})}
                   [:img {:style (style {:width :104px})
                          :src   (:image-src image-bundle)}]
                   [:div {:style (style {:margin-top :8px
                                         :color      color-gray-4})}
                    "No results"]]}))

(defn detect-pulse-card-type
  "Determine the pulse (visualization) type of a CARD, e.g. `:scalar` or `:bar`."
  [card data]
  (let [col-count (-> data :cols count)
        row-count (-> data :rows count)
        col-1 (-> data :cols first)
        col-2 (-> data :cols second)
        aggregation (-> card :dataset_query :query :aggregation first)]
    (cond
      (or (= aggregation :rows)
          (contains? #{:pin_map :state :country} (:display card))) nil
      (or (zero? row-count)
          ;; Many aggregations result in [[nil]] if there are no rows to aggregate after filters
          (= [[nil]] (-> data :rows)))                             :empty
      (and (= col-count 1)
           (= row-count 1))                                        :scalar
      (and (= col-count 2)
           (> row-count 1)
           (datetime-field? col-1)
           (number-field? col-2))                                  :sparkline
      (and (= col-count 2)
           (number-field? col-2))                                  :bar
      :else                                                        :table)))

(s/defn ^:private make-title-if-needed :- (s/maybe RenderedPulseCard)
  [render-type card]
  (when *include-title*
    (let [image-bundle (when *include-buttons*
                         (external-link-image-bundle render-type))]
      {:attachments (when image-bundle
                      (image-bundle->attachment image-bundle))
       :content     [:table {:style (style {:margin-bottom :8px
                                            :width         :100%})}
                     [:tbody
                      [:tr
                       [:td [:span {:style header-style}
                             (-> card :name h)]]
                       [:td {:style (style {:text-align :right})}
                        (when *include-buttons*
                          [:img {:style (style {:width :16px})
                                 :width 16
                                 :src   (:image-src image-bundle)}])]]]]})))

(s/defn ^:private render-pulse-card-body :- RenderedPulseCard
  [render-type timezone card {:keys [data error]}]
  (try
    (when error
      (let [^String msg (tru "Card has errors: {0}" error)]
        (throw (Exception. msg))))
    (case (detect-pulse-card-type card data)
      :empty     (render:empty     render-type card data)
      :scalar    (render:scalar    timezone card data)
      :sparkline (render:sparkline render-type timezone card data)
      :bar       (render:bar       timezone card data)
      :table     (render:table     timezone card data)
      {:attachments nil
       :content     [:div {:style (style font-style
                                         {:color       "#F9D45C"
                                          :font-weight 700})}
                     "We were unable to display this card." [:br] "Please view this card in Metabase."]})
    (catch Throwable e
      (log/error e (trs "Pulse card render error"))
      {:attachments nil
       :content     [:div {:style (style font-style
                                         {:color       "#EF8C8C"
                                          :font-weight 700
                                          :padding     :16px})}
                     "An error occurred while displaying this card."]})))

(s/defn ^:private render-pulse-card :- RenderedPulseCard
  "Render a single CARD for a `Pulse` to Hiccup HTML. RESULT is the QP results."
  [render-type timezone card results]
  (let [{title :content title-attachments :attachments} (make-title-if-needed render-type card)
        {pulse-body :content body-attachments :attachments} (render-pulse-card-body render-type timezone card results)]
    {:attachments (merge title-attachments body-attachments)
     :content     [:a {:href   (card-href card)
                       :target "_blank"
                       :style  (style section-style
                                      {:margin          :16px
                                       :margin-bottom   :16px
                                       :display         :block
                                       :text-decoration :none})}
                   title
                   pulse-body]}))

(defn render-pulse-card-for-display
  "Same as `render-pulse-card` but isn't intended for an email, rather for previewing so there is no need for
  attachments"
  [timezone card results]
  (:content (render-pulse-card :inline timezone card results)))

(s/defn render-pulse-section :- RenderedPulseCard
  "Render a specific section of a Pulse, i.e. a single Card, to Hiccup HTML."
  [timezone {:keys [card result]}]
  (let [{:keys [attachments content]} (binding [*include-title* true]
                                        (render-pulse-card :attachment timezone card result))]
    {:attachments attachments
     :content     [:div {:style (style {:margin-top       :10px
                                        :margin-bottom    :20px
                                        :border           "1px solid #dddddd"
                                        :border-radius    :2px
                                        :background-color :white
                                        :box-shadow       "0 1px 2px rgba(0, 0, 0, .08)"})}
                   content]}))

(defn render-pulse-card-to-png
  "Render a PULSE-CARD as a PNG. DATA is the `:data` from a QP result (I think...)"
  ^bytes [timezone pulse-card result]
  (render-html-to-png (render-pulse-card :inline timezone pulse-card result) card-width))
