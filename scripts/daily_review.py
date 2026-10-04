# -*- coding: utf-8 -*-
"""每日复习清单：从 tracker 数据库按失败记录调度今日该重做的题。

无状态设计，规则（基于每题的失败次数与最近一次 AC 时间）：
- 欠账：从未 AC，或最后一次失败之后还没有 AC —— 每天都列，直到做出来
- 复习：失败后已 AC，距上次 AC 达到间隔（失败 1 次 3 天、2 次 7 天、3+ 次 14 天）即到期；
  距上次 AC 超过间隔的 3 倍视为已巩固，不再列出
- 抽查：一个月前一发即中的题，每天随机抽 2 道过一遍（随机种子按日期，当天结果稳定）

用法：
    PYTHONIOENCODING=utf-8 python scripts/daily_review.py            # 今天
    PYTHONIOENCODING=utf-8 python scripts/daily_review.py 2026-10-01 # 指定日期（调试用）
"""
import random
import sys
from datetime import date, datetime

import pymysql

DB = dict(host='127.0.0.1', port=3306, database='leetcode_tracker',
          user='root', password='changeme')

RUNGS = [3, 7, 14]          # 失败 1/2/3+ 次对应的复习间隔（天）
GRACE_MULTIPLE = 3          # 超过间隔的 3 倍视为已巩固
REVIEW_DAILY_LIMIT = 6      # 隔期重做每天最多列出的题数（按逾期最久优先）
SAMPLE_ONE_SHOT = 2         # 每天抽查的一发即中题数


def load_failed_questions(cur):
    cur.execute("""
        SELECT q.id, q.title, q.url,
               SUM(s.status NOT IN ('Accepted', 'Compile Error'))      AS fails,
               MAX(CASE WHEN s.status NOT IN ('Accepted', 'Compile Error')
                        THEN s.submitted_at END)                       AS last_fail,
               MAX(CASE WHEN s.status = 'Accepted' THEN s.submitted_at END) AS last_ac,
               COUNT(*) = 1 AND MAX(s.status = 'Accepted')             AS one_shot
        FROM tracker_submission s
        JOIN tracker_question q ON q.id = s.question_id
        GROUP BY q.id
        HAVING fails > 0 OR one_shot
    """)
    return cur.fetchall()


def main():
    today = date.fromisoformat(sys.argv[1]) if len(sys.argv) > 1 else date.today()
    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    debts, reviews = [], []
    for qid, title, url, fails, last_fail, last_ac, one_shot in load_failed_questions(cur):
        fails = int(fails)
        if last_fail is None:      # 一发即中题，只进抽查池
            continue
        if last_ac is None or last_ac < last_fail:
            debts.append((title, url, fails, last_fail))
            continue
        days = (today - last_ac.date()).days
        rung = RUNGS[min(fails, 3) - 1]
        if rung <= days < rung * GRACE_MULTIPLE:
            reviews.append((title, url, fails, days, rung))

    spot_checks = []
    cur.execute("""
            SELECT q.title, q.url, MAX(s.submitted_at)
            FROM tracker_submission s
            JOIN tracker_question q ON q.id = s.question_id
            GROUP BY q.id
            HAVING COUNT(*) = 1 AND MAX(s.status = 'Accepted')
               AND MAX(s.submitted_at) BETWEEN %s - INTERVAL 45 DAY AND %s - INTERVAL 21 DAY
        """, (today, today))
    spot_pool = cur.fetchall()
    # 种子用日期，保证同一天多次运行结果一致
    rng = random.Random(today.isoformat())
    spot_checks = rng.sample(spot_pool, min(SAMPLE_ONE_SHOT, len(spot_pool)))

    print(f"== 每日复习清单 · {today} ==")
    print()
    if debts:
        print(f"【今日必做 · 欠账 {len(debts)} 题】失败后还没做出来：")
        for title, url, fails, last_fail in sorted(debts, key=lambda x: x[3]):
            print(f"  - {title}  （失败 {fails} 次，最后失败 {last_fail:%m-%d}）")
            print(f"    {url}")
        print()
    else:
        print("【今日必做】无欠账。")
        print()

    if reviews:
        # 首次运行会有历史积压，每天只给最逾期的一份量
        reviews.sort(key=lambda x: (-(x[3] - x[4]), -x[3]))
        shown, backlog = reviews[:REVIEW_DAILY_LIMIT], reviews[REVIEW_DAILY_LIMIT:]
        print(f"【隔期重做 · 今日 {len(shown)} 题 / 排队 {len(backlog)} 题】失败后 AC 过，间隔已到：")
        for title, url, fails, days, rung in shown:
            print(f"  - {title}  （曾失败 {fails} 次，距上次 AC {days} 天 / 间隔 {rung} 天）")
            print(f"    {url}")
        print()
    else:
        print("【隔期重做】今天没有到期的。")
        print()

    if spot_checks:
        print(f"【抽查 · 一发即中题回访 {len(spot_checks)} 题】：")
        for title, url, _ in spot_checks:
            print(f"  - {title}")
            print(f"    {url}")


if __name__ == '__main__':
    main()
