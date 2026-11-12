"""Audit or import reviewed search names using the standard PostgreSQL PG* environment."""

import argparse
import csv
import re
import unicodedata

import psycopg


FIELDS = ('card_id', 'language', 'name', 'source')
THAI_SUFFIX_GAP = re.compile(r'(?<=[\u0e00-\u0e7f]) (?=(?:ex|gx|vstar|vmax|v)\b)')


def read_names(paths):
    names = {}
    for path in paths:
        with open(path, encoding='utf-8-sig', newline='') as source:
            reader = csv.DictReader(source)
            if reader.fieldnames != list(FIELDS):
                raise ValueError(f'{path}: expected CSV columns {", ".join(FIELDS)}')
            for row in reader:
                location = f'{path}:{reader.line_num}'
                if None in row or any(row[field] is None for field in FIELDS):
                    raise ValueError(f'{location}: invalid CSV row')
                card_id, language, name, provenance = (row[field].strip() for field in FIELDS)
                search_name = THAI_SUFFIX_GAP.sub('', ' '.join(unicodedata.normalize('NFC', name).lower().split()))
                if (not re.fullmatch(r'[A-Za-z0-9_.:-]{1,100}', card_id) or language not in ('th', 'en')
                        or not name or not provenance or max(len(name), len(search_name), len(provenance)) > 255
                        or any(char in name + provenance for char in '\r\n\0')
                        or (language == 'th' and not re.search(r'[ก-ฮ]', name))):
                    raise ValueError(f'{location}: invalid search name')
                key = (card_id, language, search_name)
                value = (card_id, language, name, search_name, provenance)
                if key in names and names[key] != value:
                    raise ValueError(f'{location}: conflicting normalized search name')
                names[key] = value
    return names.values()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv_paths', nargs='+', help='Reviewed UTF-8 CSV files with card_id,language,name,source columns')
    parser.add_argument('--apply', action='store_true', help='Commit if all active cards are covered')
    args = parser.parse_args()
    try:
        rows = read_names(args.csv_paths)
    except (OSError, ValueError) as error:
        parser.error(str(error))

    with psycopg.connect(connect_timeout=10, client_encoding='UTF8') as db:
        db.execute('SET LOCAL lock_timeout = \'5s\'')
        db.execute('SET LOCAL statement_timeout = \'60s\'')
        if args.apply:
            db.execute('LOCK TABLE global_cards IN SHARE MODE')
            db.execute('LOCK TABLE card_search_names IN SHARE ROW EXCLUSIVE MODE')
        db.execute('''CREATE TEMP TABLE incoming_card_names (
            card_id text, language text, name text, search_name text, source text
        ) ON COMMIT DROP''')
        with db.cursor().copy('COPY incoming_card_names FROM STDIN') as copy:
            for row in rows:
                copy.write_row(row)

        active, unknown, missing = db.execute('''
            SELECT (SELECT count(*) FROM global_cards WHERE is_active),
                   (SELECT count(DISTINCT n.card_id) FROM incoming_card_names n
                    WHERE NOT EXISTS (SELECT 1 FROM global_cards c WHERE c.id = n.card_id)),
                   (SELECT count(*) FROM global_cards c WHERE c.is_active
                    AND NOT EXISTS (SELECT 1 FROM incoming_card_names n WHERE n.card_id = c.id AND n.language = 'th')
                    AND NOT EXISTS (SELECT 1 FROM card_search_names n WHERE n.card_id = c.id AND n.language = 'th'))
        ''').fetchone()
        print(f'Active cards: {active}; input names: {len(rows)}; unknown IDs: {unknown}; missing Thai: {missing}')
        if unknown or missing or not active:
            return 1
        if args.apply:
            inserted = db.execute('''
                INSERT INTO card_search_names (card_id, language, name, search_name, source)
                SELECT card_id, language, name, search_name, source FROM incoming_card_names
                ON CONFLICT (card_id, language, search_name) DO NOTHING
            ''').rowcount
            print(f'Inserted search names: {inserted}')
        return 0


if __name__ == '__main__':
    raise SystemExit(main())
