import Foundation

/// Finds the song the learner asked for by voice ("mở bài ngôi sao lấp lánh", "play twinkle twinkle").
/// Accents and case are ignored, filler words ("mở bài", "play") are dropped, and each song is
/// scored on its title, its subtitle (the English name) and both together. Mirrors SongMatcher.kt.
enum SongMatcher {
    struct Song: Equatable { let key: String; let title: String; var subtitle: String = "" }
    struct Match { let song: Song; let score: Double }

    static let minScore = 0.55

    private static let filler: Set<String> = [
        "mo", "bai", "hat", "choi", "phat", "cho", "toi", "minh", "em", "nhe", "di", "voi", "nhac", "ban", "nghe",
        "hay", "giup", "bat", "dau", "tap", "luyen", "song", "play", "open", "the", "a", "please", "start",
        "practice", "piece", "lesson",
    ]

    /// Lower-case, no accents (đ → d), only letters/digits separated by single spaces.
    static func normalize(_ text: String) -> String {
        let folded = text.replacingOccurrences(of: "đ", with: "d").replacingOccurrences(of: "Đ", with: "D")
            .folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .lowercased()
        let parts = folded.unicodeScalars.split { !CharacterSet.alphanumerics.contains($0) }
        return parts.map { String($0) }.joined(separator: " ")
    }

    private static func tokens(_ text: String, dropFiller: Bool) -> [String] {
        let t = normalize(text).split(separator: " ").map(String.init)
        guard dropFiller else { return t }
        let kept = t.filter { !filler.contains($0) }
        return kept.isEmpty ? t : kept
    }

    static func rank(_ query: String, _ songs: [Song]) -> [Match] {
        let q = tokens(query, dropFiller: true)
        guard !q.isEmpty else { return [] }
        return songs.map { s in
            let title = tokens(s.title, dropFiller: false), sub = tokens(s.subtitle, dropFiller: false)
            return Match(song: s, score: max(score(q, title), score(q, sub) * 0.95, score(q, title + sub) * 0.97))
        }.filter { $0.score > 0 }.sorted { $0.score > $1.score }
    }

    static func best(_ query: String, _ songs: [Song]) -> Song? {
        guard let m = rank(query, songs).first, m.score >= minScore else { return nil }
        return m.song
    }

    static func isAmbiguous(_ ranked: [Match]) -> Bool {
        ranked.count >= 2 && ranked[0].score - ranked[1].score < 0.02 && ranked[0].score < 0.999
    }

    private static func score(_ q: [String], _ c: [String]) -> Double {
        guard !c.isEmpty else { return 0 }
        let qs = q.joined(separator: " "), cs = c.joined(separator: " ")
        if qs == cs { return 1 }
        var hits = 0.0
        var used = [Bool](repeating: false, count: c.count)
        for w in q {
            var bestJ = -1, bestSim = 0.0
            for (j, cw) in c.enumerated() where !used[j] {
                let sim = wordSim(w, cw)
                if sim > bestSim { bestSim = sim; bestJ = j }
            }
            if bestJ >= 0 && bestSim >= 0.75 { used[bestJ] = true; hits += bestSim }
        }
        let precision = hits / Double(q.count), recall = hits / Double(c.count)
        let f1 = hits == 0 ? 0 : 2 * precision * recall / (precision + recall)
        let covered = precision >= 0.999 ? 0.7 + 0.3 * recall : 0
        let contained = cs.count >= 4 && (" " + qs + " ").contains(" " + cs + " ") ? 0.97 : 0
        let chars = 1 - Double(levenshtein(qs, cs)) / Double(max(qs.count, cs.count))
        return max(f1, covered, contained, chars * 0.9)
    }

    private static func wordSim(_ a: String, _ b: String) -> Double {
        if a == b { return 1 }
        if a.count < 3 || b.count < 3 { return 0 }
        return 1 - Double(levenshtein(a, b)) / Double(max(a.count, b.count))
    }

    static func levenshtein(_ a: String, _ b: String) -> Int {
        let a = Array(a), b = Array(b)
        if a == b { return 0 }
        if a.isEmpty { return b.count }
        if b.isEmpty { return a.count }
        var prev = Array(0...b.count), cur = [Int](repeating: 0, count: b.count + 1)
        for i in 1...a.count {
            cur[0] = i
            for j in 1...b.count {
                cur[j] = min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1))
            }
            swap(&prev, &cur)
        }
        return prev[b.count]
    }
}
