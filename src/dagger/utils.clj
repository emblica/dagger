(ns dagger.utils)



(defn mapmap-kv [f m]
  "maps hashmaps with pairs"
  (reduce-kv (fn [prev k v]
                (let [[n-k n-v] (f k v)]
                  (assoc prev n-k n-v))) {} m))

(defn mapmap [f m]
  "maps hashmaps"
  (mapmap-kv (fn [k v] (list k (f v))) m))


(defn index-by [prop l]
  (->> l
    (group-by prop)
    (mapmap first)))

(defn deep-merge [a b]
  (merge-with (fn [x y]
                (cond (map? y) (deep-merge x y)
                      (vector? y) (concat x y)
                      :else y))
             a b))


(defn now []
  (System/currentTimeMillis))
