import SwiftUI

/// Library > Calendar: the days ahead with the episodes and movies that come out, then the past week.
struct CalendarView: View {
    @EnvironmentObject var store: Store
    @ObservedObject private var calendar = ReleaseCalendar.shared

    var body: some View {
        let sections = calendar.sections
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if sections.isEmpty {
                    if calendar.loading || !calendar.loaded {
                        HStack { Spacer(); ProgressView().tint(Theme.red); Spacer() }.padding(.top, 40)
                    } else {
                        Text("Nothing coming up. New episodes and movies for what's in your Library show up here.")
                            .foregroundColor(Theme.dim).multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity).padding(.horizontal, 24).padding(.top, 40)
                    }
                }
                ForEach(sections) { s in
                    Text(s.heading).font(.headline).foregroundColor(.white)
                        .padding(.horizontal, 16).padding(.top, 18).padding(.bottom, 8)
                    ForEach(s.items) { i in
                        NavigationLink(value: meta(i)) { CalendarRow(item: i, showDay: s.day == "past") }
                            .buttonStyle(.plain)
                    }
                }
            }
            .padding(.bottom, 24)
        }
        .background(Theme.bg.ignoresSafeArea())
        .navigationTitle("Calendar")
        .profileToolbar()
        .refreshable { calendar.refresh(store, force: true) }
        .onAppear { calendar.refresh(store) }
    }

    private func meta(_ i: ReleaseCalendar.Item) -> Meta {
        store.entry(i.showId)?.meta ?? Meta.parse(["id": i.showId, "type": i.type, "name": i.name, "poster": i.poster ?? ""])
    }
}

struct CalendarRow: View {
    let item: ReleaseCalendar.Item
    var showDay = false

    var body: some View {
        HStack(spacing: 12) {
            RemoteImage(url: item.poster)
                .frame(width: 46, height: 69)
                .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 3) {
                Text(item.name).font(.system(size: 15, weight: .semibold)).foregroundColor(.white).lineLimit(1)
                Text(item.line).font(.system(size: 13)).foregroundColor(Theme.dim).lineLimit(2)
            }
            Spacer(minLength: 8)
            if showDay {
                Text(ReleaseCalendar.shortDay(item.day)).font(.system(size: 13)).foregroundColor(Theme.faint)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 6)
        .contentShape(Rectangle())
    }
}

/// The Calendar row at the top of the Library: what comes out next.
struct CalendarLink: View {
    @EnvironmentObject var store: Store
    @ObservedObject private var calendar = ReleaseCalendar.shared

    var body: some View {
        NavigationLink(value: CalendarTarget()) {
            HStack(spacing: 12) {
                Image(systemName: "calendar").font(.system(size: 20)).foregroundColor(Theme.red).frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Calendar").font(.system(size: 16, weight: .semibold)).foregroundColor(.white)
                    Text(subtitle).font(.system(size: 13)).foregroundColor(Theme.dim).lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundColor(Color(hex: 0x8E8E93))
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0x1C1C1E)))
            .padding(.horizontal, 16)
        }
        .buttonStyle(.plain)
        .onAppear { calendar.refresh(store) }
    }

    private var subtitle: String {
        if let n = calendar.next {
            return "Next: " + n.name + (n.type == "movie" ? "" : " S\(n.season) E\(n.episode)") + " · " + ReleaseCalendar.shortDay(n.day)
        }
        return calendar.loaded ? "Upcoming episodes and movies" : "Looking for release dates…"
    }
}

/// Opens the Calendar in the Library's navigation stack.
struct CalendarTarget: Hashable {}
